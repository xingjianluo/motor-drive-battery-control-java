package com.github.xingjianluo.motordrivebatterycontroljava.event;

/**
 * 租约到期回收事件：预留到期前未结算也未撤销，系统自动回收，
 * 冻结能量全额放回可调度池（refundedWh = reservedWh）。终止态，不可再激活。
 */
public record ReservationExpired(long sequence,
                                 long timestampMs,
                                 String requestId,
                                 String sourceId,
                                 double refundedWh,
                                 long expiredAtMs) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "EXPIRED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId, Double.toString(refundedWh),
                Long.toString(expiredAtMs));
    }

    public static ReservationExpired fromFields(String[] f, long seq, long ts) {
        return new ReservationExpired(seq, ts, f[3], f[4],
                Double.parseDouble(f[5]), Long.parseLong(f[6]));
    }
}
