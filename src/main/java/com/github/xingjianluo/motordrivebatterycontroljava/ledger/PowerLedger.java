package com.github.xingjianluo.motordrivebatterycontroljava.ledger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationExpired;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationReleased;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationRenewed;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationReserved;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationSettled;
import com.github.xingjianluo.motordrivebatterycontroljava.time.TimeSource;

/**
 * 功率预留账本：所有预留/撤销/结算/续期/回收在协调器同一把锁内串行调用。
 * 账本是事件的投影：reserve/release/settle/renew/expireDue 先落事件再投影；
 * 重放时只调用 applyEvent，不重复写日志，因此状态可复现。
 *
 * 能量守恒：reservedWh 在预留时冻结（由协调器同步调用 battery 扣减/返还）；
 * 撤销全额返还；结算按实际耗能扣减、差额返还；过期回收与撤销同口径全额返还。
 *
 * 租约：reserve 可带 expiresAtEpochMs；到期未结算/未撤销的预留由 expireDue
 * 在锁内原子回收（EXPIRED 终止态），此后不可再被续期/撤销/结算，也不会重复释放。
 */
public class PowerLedger {

    public enum ReleaseOutcome { RELEASED, NOT_FOUND, ALREADY_RELEASED, ALREADY_SETTLED, ALREADY_EXPIRED }

    public enum SettleOutcome { SETTLED, NOT_FOUND, ALREADY_SETTLED, ALREADY_RELEASED, ALREADY_EXPIRED }

    public enum RenewOutcome { RENEWED, NOT_FOUND, ALREADY_RELEASED, ALREADY_SETTLED, ALREADY_EXPIRED }

    public record SettleResult(SettleOutcome outcome, double refundedWh) { }

    public record RenewResult(RenewOutcome outcome, long expiresAtEpochMs) { }

    private final EventStore eventStore;
    private final TimeSource timeSource;
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private long nextSequence = 1;

    public PowerLedger(EventStore eventStore, TimeSource timeSource) {
        this.eventStore = eventStore;
        this.timeSource = timeSource;
    }

    private long nowMs() {
        return timeSource.nowMs();
    }

    private long nextSeq() {
        // 序号以事件日志为准（重放后接着递增）
        long last = eventStore.lastSequence();
        return Math.max(nextSequence, last + 1);
    }

    /** 冻结一笔预留能量：写事件并投影。expiresAtEpochMs 为租约到期时刻。返回已落库的预留。 */
    public Reservation reserve(String requestId, String sourceId,
                               double requestedPowerW, double grantedPowerW,
                               long durationMs, double reservedWh, long expiresAtEpochMs) {
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationReserved event = new ReservationReserved(seq, nowMs(),
                requestId, sourceId, requestedPowerW, grantedPowerW, durationMs, reservedWh,
                expiresAtEpochMs);
        eventStore.append(event);
        applyEvent(event);
        return reservations.get(requestId);
    }

    /**
     * 撤销并全额返还；幂等：对终止态或不存在的请求，不产生事件、不重复返还。
     * sourceId 必须与预留时一致，防止其它入口误撤。
     * 若租约已到期，先原子回收再返回 ALREADY_EXPIRED（不会按撤销重复释放）。
     */
    public ReleaseOutcome release(String requestId, String sourceId) {
        Reservation r = reservations.get(requestId);
        if (r == null || !r.sourceId().equals(sourceId)) {
            return ReleaseOutcome.NOT_FOUND;
        }
        if (r.status() == ReservationStatus.RELEASED) {
            return ReleaseOutcome.ALREADY_RELEASED;
        }
        if (r.status() == ReservationStatus.SETTLED) {
            return ReleaseOutcome.ALREADY_SETTLED;
        }
        if (r.status() == ReservationStatus.EXPIRED) {
            return ReleaseOutcome.ALREADY_EXPIRED;
        }
        if (r.isExpiredAt(nowMs())) {
            expireOne(r);
            return ReleaseOutcome.ALREADY_EXPIRED;
        }
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationReleased event = new ReservationReleased(seq, nowMs(),
                requestId, sourceId, r.reservedWh());
        eventStore.append(event);
        applyEvent(event);
        return ReleaseOutcome.RELEASED;
    }

    /**
     * 结算：actualConsumedWh 实扣，reservedWh - actualConsumedWh 差额返还。
     * 实际耗能不得超过预留（协调器保证授予功率不超限），下限 0。
     * 已过期回收的预留不能再结算（返回 ALREADY_EXPIRED），避免回收后重复动账。
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
        if (r.status() == ReservationStatus.EXPIRED) {
            return new SettleResult(SettleOutcome.ALREADY_EXPIRED, 0);
        }
        if (r.isExpiredAt(nowMs())) {
            expireOne(r);
            return new SettleResult(SettleOutcome.ALREADY_EXPIRED, 0);
        }
        double consumed = Math.min(actualConsumedWh, r.reservedWh());
        double refunded = r.reservedWh() - consumed;
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationSettled event = new ReservationSettled(seq, nowMs(),
                requestId, sourceId, r.reservedWh(), consumed, refunded);
        eventStore.append(event);
        applyEvent(event);
        return new SettleResult(SettleOutcome.SETTLED, refunded);
    }

    /**
     * 续期：只把有效期延长 extendMs，不重复冻结能量、不改变已授予功率。
     * 新到期时刻 = max(当前时刻, 旧到期时刻) + extendMs（饱和到 Long.MAX_VALUE）。
     * 对不带有效期的预留续期：返回 RENEWED 但保持永不过期（不产生事件、不动账）。
     * 对已过期/已撤销/已结算的预留：返回可区分的结果码，不重新激活、不产生事件。
     */
    public RenewResult renew(String requestId, String sourceId, long extendMs) {
        if (extendMs <= 0) {
            throw new IllegalArgumentException("续期时长必须为正: " + extendMs);
        }
        Reservation r = reservations.get(requestId);
        if (r == null || !r.sourceId().equals(sourceId)) {
            return new RenewResult(RenewOutcome.NOT_FOUND, Reservation.NEVER_EXPIRES);
        }
        if (r.status() == ReservationStatus.RELEASED) {
            return new RenewResult(RenewOutcome.ALREADY_RELEASED, r.expiresAtEpochMs());
        }
        if (r.status() == ReservationStatus.SETTLED) {
            return new RenewResult(RenewOutcome.ALREADY_SETTLED, r.expiresAtEpochMs());
        }
        if (r.status() == ReservationStatus.EXPIRED) {
            return new RenewResult(RenewOutcome.ALREADY_EXPIRED, r.expiresAtEpochMs());
        }
        if (r.isExpiredAt(nowMs())) {
            expireOne(r);
            return new RenewResult(RenewOutcome.ALREADY_EXPIRED, r.expiresAtEpochMs());
        }
        if (!r.hasLease()) {
            // 无有效期预留：续期不改变语义（仍然永不过期），不动账、不写事件
            return new RenewResult(RenewOutcome.RENEWED, Reservation.NEVER_EXPIRES);
        }
        long base = Math.max(nowMs(), r.expiresAtEpochMs());
        long newExpires = saturatedAdd(base, extendMs);
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationRenewed event = new ReservationRenewed(seq, nowMs(),
                requestId, sourceId, r.expiresAtEpochMs(), newExpires);
        eventStore.append(event);
        applyEvent(event);
        return new RenewResult(RenewOutcome.RENEWED, newExpires);
    }

    /**
     * 回收所有在 nowMs 时刻（含）已到期但仍处于 RESERVED 的预留。
     * 在协调器锁内调用，与并发请求/撤销/结算共用同一账本口径；
     * 幂等：状态不是 RESERVED 的永不再回收，因此不会重复释放。
     *
     * @return 本次实际产生的过期回收事件
     */
    public List<ReservationExpired> expireDue(long nowMs) {
        List<ReservationExpired> expired = new ArrayList<>();
        for (Reservation r : List.copyOf(reservations.values())) {
            if (r.status() == ReservationStatus.RESERVED && r.isExpiredAt(nowMs)) {
                long seq = nextSeq();
                nextSequence = seq + 1;
                ReservationExpired event = new ReservationExpired(seq, nowMs,
                        r.requestId(), r.sourceId(), r.reservedWh(), r.expiresAtEpochMs());
                eventStore.append(event);
                applyEvent(event);
                expired.add(event);
            }
        }
        return expired;
    }

    /** 回收单笔已到期预留（防御路径：调用方未先统一 sweep 时也不会重复释放）。 */
    private void expireOne(Reservation r) {
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationExpired event = new ReservationExpired(seq, nowMs(),
                r.requestId(), r.sourceId(), r.reservedWh(), r.expiresAtEpochMs());
        eventStore.append(event);
        applyEvent(event);
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < a ? Long.MAX_VALUE : sum;
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

    /** 已冻结（未结算/未撤销/未回收）的总能量 Wh。 */
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
                e.reservedWh(), ReservationStatus.RESERVED, e.sequence(),
                e.expiresAtEpochMs());
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

    public void applyEvent(ReservationRenewed e) {
        Reservation r = reservations.get(e.requestId());
        if (r != null) {
            reservations.put(e.requestId(), r.withExpiry(e.newExpiresAtEpochMs()));
        }
        bumpSequence(e.sequence());
    }

    public void applyEvent(ReservationExpired e) {
        Reservation r = reservations.get(e.requestId());
        if (r != null && r.status() == ReservationStatus.RESERVED) {
            reservations.put(e.requestId(), r.withStatus(ReservationStatus.EXPIRED));
        }
        bumpSequence(e.sequence());
    }

    private void bumpSequence(long seq) {
        nextSequence = Math.max(nextSequence, seq + 1);
    }
}
