package com.github.xingjianluo.motordrivebatterycontroljava.event;

/**
 * 续期事件：仅延长有效期，不重复冻结能量、不改变已授予功率。
 * 只在预留仍处于 RESERVED 且未到期时产生；对终止态/已过期预留不会产生该事件。
 */
public record ReservationRenewed(long sequence,
                                 long timestampMs,
                                 String requestId,
                                 String sourceId,
                                 long previousExpiresAtMs,
                                 long newExpiresAtMs) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "RENEWED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId,
                Long.toString(previousExpiresAtMs), Long.toString(newExpiresAtMs));
    }

    public static ReservationRenewed fromFields(String[] f, long seq, long ts) {
        return new ReservationRenewed(seq, ts, f[3], f[4],
                Long.parseLong(f[5]), Long.parseLong(f[6]));
    }
}
