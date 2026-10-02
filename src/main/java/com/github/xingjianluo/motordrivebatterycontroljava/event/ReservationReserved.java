package com.github.xingjianluo.motordrivebatterycontroljava.event;

/**
 * 预留成功事件：冻结 reservedWh 能量。
 *
 * @param leaseExpiresAtMs 租约到期时刻（epoch 毫秒）；0 表示不带有效期。
 *                         直接记录到期时刻（而非 TTL），保证重放结果与时钟无关、可复现。
 */
public record ReservationReserved(long sequence,
                                  long timestampMs,
                                  String requestId,
                                  String sourceId,
                                  double requestedPowerW,
                                  double grantedPowerW,
                                  long durationMs,
                                  double reservedWh,
                                  long leaseExpiresAtMs) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "RESERVED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId,
                Double.toString(requestedPowerW), Double.toString(grantedPowerW),
                Long.toString(durationMs), Double.toString(reservedWh),
                Long.toString(leaseExpiresAtMs));
    }

    public static ReservationReserved fromFields(String[] f, long seq, long ts) {
        // 兼容旧格式日志（无租约字段）：缺失时按不带有效期处理
        long leaseExpiresAtMs = f.length > 9 ? Long.parseLong(f[9]) : 0L;
        return new ReservationReserved(seq, ts, f[3], f[4],
                Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                Long.parseLong(f[7]), Double.parseDouble(f[8]), leaseExpiresAtMs);
    }
}
