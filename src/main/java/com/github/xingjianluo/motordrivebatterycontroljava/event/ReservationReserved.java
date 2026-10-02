package com.github.xingjianluo.motordrivebatterycontroljava.event;

import com.github.xingjianluo.motordrivebatterycontroljava.ledger.Reservation;

/**
 * 预留成功事件：冻结 reservedWh 能量。
 *
 * @param expiresAtEpochMs 租约到期时刻；旧版日志无该字段时按
 *                         {@link Reservation#NEVER_EXPIRES} 解析（不带有效期，兼容）
 */
public record ReservationReserved(long sequence,
                                  long timestampMs,
                                  String requestId,
                                  String sourceId,
                                  double requestedPowerW,
                                  double grantedPowerW,
                                  long durationMs,
                                  double reservedWh,
                                  long expiresAtEpochMs) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "RESERVED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId,
                Double.toString(requestedPowerW), Double.toString(grantedPowerW),
                Long.toString(durationMs), Double.toString(reservedWh),
                Long.toString(expiresAtEpochMs));
    }

    public static ReservationReserved fromFields(String[] f, long seq, long ts) {
        long expiresAt = f.length > 9
                ? Long.parseLong(f[9])
                : Reservation.NEVER_EXPIRES;
        return new ReservationReserved(seq, ts, f[3], f[4],
                Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                Long.parseLong(f[7]), Double.parseDouble(f[8]), expiresAt);
    }
}
