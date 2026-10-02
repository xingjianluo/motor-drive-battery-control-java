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
 * 功率预留账本：所有预留/续期/撤销/结算/到期回收在协调器同一把锁内串行调用。
 * 账本是事件的投影：reserve/renew/release/settle/expire 先落事件再投影；
 * 重放时只调用 applyEvent，不重复写日志，因此状态可复现。
 *
 * 能量守恒：reservedWh 在预留时冻结（由协调器同步调用 battery 扣减/返还）；
 * 撤销与到期回收全额返还；结算按实际耗能扣减、差额返还。
 *
 * 租约：预留可带到期时刻 leaseExpiresAtMs（0=不带有效期）。到期未结算/未撤销的
 * 预留由 expireDue 回收（写 EXPIRED 事件，进入终止态，冻结能量返还）；
 * 续期只写 RENEWED 事件延长到期时刻，不触碰冻结能量与授予功率。
 */
public class PowerLedger {

    public enum ReleaseOutcome { RELEASED, NOT_FOUND, ALREADY_RELEASED, ALREADY_SETTLED, ALREADY_EXPIRED }

    public enum SettleOutcome { SETTLED, NOT_FOUND, ALREADY_SETTLED, ALREADY_RELEASED, ALREADY_EXPIRED }

    public enum RenewOutcome { RENEWED, NOT_FOUND, NO_LEASE, ALREADY_EXPIRED, ALREADY_RELEASED, ALREADY_SETTLED }

    public record SettleResult(SettleOutcome outcome, double refundedWh) { }

    public record RenewResult(RenewOutcome outcome, long newExpiresAtMs) { }

    private final EventStore eventStore;
    private final TimeSource clock;
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private long nextSequence = 1;

    public PowerLedger(EventStore eventStore, TimeSource clock) {
        this.eventStore = eventStore;
        this.clock = clock;
    }

    private long nextSeq() {
        // 序号以事件日志为准（重放后接着递增）
        long last = eventStore.lastSequence();
        return Math.max(nextSequence, last + 1);
    }

    /** 冻结一笔预留能量：写事件并投影。leaseTtlMs<=0 表示不带有效期。返回已落库的预留。 */
    public Reservation reserve(String requestId, String sourceId,
                               double requestedPowerW, double grantedPowerW,
                               long durationMs, double reservedWh, long leaseTtlMs) {
        long now = clock.nowMs();
        long leaseExpiresAtMs = leaseTtlMs > 0 ? now + leaseTtlMs : 0;
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationReserved event = new ReservationReserved(seq, now,
                requestId, sourceId, requestedPowerW, grantedPowerW, durationMs,
                reservedWh, leaseExpiresAtMs);
        eventStore.append(event);
        applyEvent(event);
        return reservations.get(requestId);
    }

    /**
     * 续期：只延长有效期（newExpires = 当前到期时刻 + extendByMs），
     * 不重复冻结能量、不改变已授予功率。幂等安全：对终止态/已过期/无租约的预留
     * 返回可区分结果，不产生事件、不重新激活。
     */
    public RenewResult renew(String requestId, String sourceId, long extendByMs) {
        if (extendByMs <= 0) {
            throw new IllegalArgumentException("续期时长必须为正: " + extendByMs);
        }
        Reservation r = reservations.get(requestId);
        if (r == null || !r.sourceId().equals(sourceId)) {
            return new RenewResult(RenewOutcome.NOT_FOUND, 0);
        }
        if (!r.hasLease()) {
            return new RenewResult(RenewOutcome.NO_LEASE, 0);
        }
        switch (r.status()) {
            case EXPIRED -> { return new RenewResult(RenewOutcome.ALREADY_EXPIRED, 0); }
            case RELEASED -> { return new RenewResult(RenewOutcome.ALREADY_RELEASED, 0); }
            case SETTLED -> { return new RenewResult(RenewOutcome.ALREADY_SETTLED, 0); }
            default -> { }
        }
        long now = clock.nowMs();
        if (r.leaseDue(now)) {
            // 已到期但尚未被回收：先按到期处理（由调用方先 sweep；此处兜底不激活）
            return new RenewResult(RenewOutcome.ALREADY_EXPIRED, 0);
        }
        long newExpiresAtMs = r.leaseExpiresAtMs() + extendByMs;
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationRenewed event = new ReservationRenewed(seq, now,
                requestId, sourceId, r.leaseExpiresAtMs(), newExpiresAtMs);
        eventStore.append(event);
        applyEvent(event);
        return new RenewResult(RenewOutcome.RENEWED, newExpiresAtMs);
    }

    /**
     * 到期回收：把 now 之前已到期、仍处 RESERVED 的预留全部回收（全额返还冻结）。
     * 返回被回收的预留快照（回收前）。幂等：已回收/终止态不会再次产生事件。
     */
    public List<Reservation> expireDue(long nowMs) {
        List<Reservation> expired = new ArrayList<>();
        for (Reservation r : List.copyOf(reservations.values())) {
            if (r.status() == ReservationStatus.RESERVED && r.leaseDue(nowMs)) {
                long seq = nextSeq();
                nextSequence = seq + 1;
                ReservationExpired event = new ReservationExpired(seq, clock.nowMs(),
                        r.requestId(), r.sourceId(), r.reservedWh(), r.leaseExpiresAtMs());
                eventStore.append(event);
                applyEvent(event);
                expired.add(r);
            }
        }
        return expired;
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
        if (r.status() == ReservationStatus.EXPIRED) {
            return ReleaseOutcome.ALREADY_EXPIRED;
        }
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationReleased event = new ReservationReleased(seq, clock.nowMs(),
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
        if (r.status() == ReservationStatus.EXPIRED) {
            return new SettleResult(SettleOutcome.ALREADY_EXPIRED, 0);
        }
        double consumed = Math.min(actualConsumedWh, r.reservedWh());
        double refunded = r.reservedWh() - consumed;
        long seq = nextSeq();
        nextSequence = seq + 1;
        ReservationSettled event = new ReservationSettled(seq, clock.nowMs(),
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

    /** 当前处于 RESERVED 状态的预留（按到达顺序）。已过期被回收的不在内。 */
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
                e.leaseExpiresAtMs());
        reservations.put(e.requestId(), r);
        bumpSequence(e.sequence());
    }

    public void applyEvent(ReservationRenewed e) {
        Reservation r = reservations.get(e.requestId());
        if (r != null && r.status() == ReservationStatus.RESERVED) {
            reservations.put(e.requestId(), r.withLeaseExpiresAt(e.newExpiresAtMs()));
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
