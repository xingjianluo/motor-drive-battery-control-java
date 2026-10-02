package com.github.xingjianluo.motordrivebatterycontroljava.event;

/**
 * 租约过期回收事件：到期未结算/未撤销时自动追加，
 * 冻结的 reservedWh 全部返还可调度池。该预留此后不再是活动占用，且不可重新激活。
 */
public record ReservationExpired(long sequence,
                                 long timestampMs,
                                 String requestId,
                                 String sourceId,
                                 double refundedWh,
                                 long expiresAtEpochMs) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "EXPIRED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId,
                Double.toString(refundedWh), Long.toString(expiresAtEpochMs));
    }

    public static ReservationExpired fromFields(String[] f, long seq, long ts) {
        return new ReservationExpired(seq, ts, f[3], f[4],
                Double.parseDouble(f[5]), Long.parseLong(f[6]));
    }
}
