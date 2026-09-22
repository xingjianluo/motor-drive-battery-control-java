package com.github.xingjianluo.motordrivebatterycontroljava.ledger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationReleased;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationReserved;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationSettled;

/**
 * 功率预留账本：所有预留/撤销/结算在协调器同一把锁内串行调用。
 * 账本是事件的投影：reserve/release/settle 先落事件再投影；
 * 重放时只调用 applyEvent，不重复写日志，因此状态可复现。
 *
 * 能量守恒：reservedWh 在预留时冻结（由协调器同步调用 battery 扣减/返还）；
 * 撤销全额返还；结算按实际耗能扣减、差额返还。
 */
public class PowerLedger {

    public enum ReleaseOutcome { RELEASED, NOT_FOUND, ALREADY_RELEASED, ALREADY_SETTLED }

    public enum SettleOutcome { SETTLED, NOT_FOUND, ALREADY_SETTLED, ALREADY_RELEASED }

    public record SettleResult(SettleOutcome outcome, double refundedWh) { }

    private final EventStore eventStore;
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private long nextSequence = 1;

    public PowerLedger(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    private long nextSeq() {
        // 序号以事件日志为准（重放后接着递增）
        long last = eventStore.lastSequence();
        return Math.max(nextSequence, last + 1);
    }

    /** 冻结一笔预留能量：写事件并投影。返回已落库的预留。 */
    public Reservation reserve(String requestId, String sourceId,
                               double requestedPowerW, double grantedPowerW,
                               long durationMs, double reservedWh) {
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationReserved event = new ReservationReserved(seq, System.currentTimeMillis(),
                requestId, sourceId, requestedPowerW, grantedPowerW, durationMs, reservedWh);
        eventStore.append(event);
        applyEvent(event);
        return reservations.get(requestId);
    }

    /**
     * 撤销并全额返还；幂等：对终止态或不存在的请求，不产生事件、不重复返还。
     * sourceId 必须与预留时一致，防止其它入口误撤。
     */
    public ReleaseOutcome release(String requestId, String sourceId) {
        Reservation r = reservations.get(requestId);
        if (r == null) {
            return ReleaseOutcome.NOT_FOUND;
        }
        if (!r.sourceId().equals(sourceId)) {
            return ReleaseOutcome.NOT_FOUND;
        }
        if (r.status() == ReservationStatus.RELEASED) {
            return ReleaseOutcome.ALREADY_RELEASED;
        }
        if (r.status() == ReservationStatus.SETTLED) {
            return ReleaseOutcome.ALREADY_SETTLED;
        }
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationReleased event = new ReservationReleased(seq, System.currentTimeMillis(),
                requestId, sourceId, r.reservedWh());
        eventStore.append(event);
        applyEvent(event);
        return ReleaseOutcome.RELEASED;
    }

    /**
     * 结算：actualConsumedWh 实扣，reservedWh - actualConsumedWh 差额返还。
     * 实际耗能不得超过预留（协调器保证授予功率不超限），下限 0。
     */
    public SettleResult settle(String requestId, String sourceId, double actualConsumedWh) {
        if (actualConsumedWh < 0) {
            throw new IllegalArgumentException("结算耗能不能为负: " + actualConsumedWh);
        }
        Reservation r = reservations.get(requestId);
        if (r == null || !r.sourceId().equals(sourceId)) {
            return new SettleResult(SettleOutcome.NOT_FOUND, 0);
        }
        if (r.status() == ReservationStatus.SETTLED) {
            return new SettleResult(SettleOutcome.ALREADY_SETTLED, 0);
        }
        if (r.status() == ReservationStatus.RELEASED) {
            return new SettleResult(SettleOutcome.ALREADY_RELEASED, 0);
        }
        double consumed = Math.min(actualConsumedWh, r.reservedWh());
        double refunded = r.reservedWh() - consumed;
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationSettled event = new ReservationSettled(seq, System.currentTimeMillis(),
                requestId, sourceId, r.reservedWh(), consumed, refunded);
        eventStore.append(event);
        applyEvent(event);
        return new SettleResult(SettleOutcome.SETTLED, refunded);
    }

    public Optional<Reservation> find(String requestId) {
        return Optional.ofNullable(reservations.get(requestId));
    }

    public boolean exists(String requestId) {
        return reservations.containsKey(requestId);
    }

    /** 当前处于 RESERVED 状态的预留（按到达顺序）。 */
    public List<Reservation> activeReservations() {
        return reservations.values().stream()
                .filter(r -> r.status() == ReservationStatus.RESERVED)
                .toList();
    }

    /** 已冻结（未结算/未撤销）的总能量 Wh。 */
    public double frozenWh() {
        return reservations.values().stream()
                .filter(r -> r.status() == ReservationStatus.RESERVED)
                .mapToDouble(Reservation::reservedWh)
                .sum();
    }

    public long version() {
        return eventStore.lastSequence();
    }

    // ---- 事件投影（重放路径与正常路径共用，保证重建结果一致） ----

    public void applyEvent(ReservationReserved e) {
        Reservation r = new Reservation(e.requestId(), e.sourceId(),
                e.requestedPowerW(), e.grantedPowerW(), e.durationMs(),
                e.reservedWh(), ReservationStatus.RESERVED, e.sequence());
        reservations.put(e.requestId(), r);
        bumpSequence(e.sequence());
    }

    public void applyEvent(ReservationReleased e) {
        Reservation r = reservations.get(e.requestId());
        if (r != null) {
            reservations.put(e.requestId(), r.withStatus(ReservationStatus.RELEASED));
        }
        bumpSequence(e.sequence());
    }

    public void applyEvent(ReservationSettled e) {
        Reservation r = reservations.get(e.requestId());
        if (r != null) {
            reservations.put(e.requestId(), r.withStatus(ReservationStatus.SETTLED));
        }
        bumpSequence(e.sequence());
    }

    private void bumpSequence(long seq) {
        nextSequence = Math.max(nextSequence, seq + 1);
    }
}
