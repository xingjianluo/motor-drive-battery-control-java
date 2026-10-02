package com.github.xingjianluo.motordrivebatterycontroljava.event;

/**
 * 租约续期事件：只延长有效期（newExpiresAtEpochMs），
 * 不重复冻结能量、不改变已授予功率。每次成功续期追加一条。
 */
public record ReservationRenewed(long sequence,
                                 long timestampMs,
                                 String requestId,
                                 String sourceId,
                                 long oldExpiresAtEpochMs,
                                 long newExpiresAtEpochMs) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "RENEWED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId,
                Long.toString(oldExpiresAtEpochMs), Long.toString(newExpiresAtEpochMs));
    }

    public static ReservationRenewed fromFields(String[] f, long seq, long ts) {
        return new ReservationRenewed(seq, ts, f[3], f[4],
                Long.parseLong(f[5]), Long.parseLong(f[6]));
    }
}
