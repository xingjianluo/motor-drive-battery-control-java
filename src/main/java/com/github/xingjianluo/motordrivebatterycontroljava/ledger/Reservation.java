package com.github.xingjianluo.motordrivebatterycontroljava.ledger;

/**
 * 一笔功率预留（账本条目，不可变）。
 *
 * @param leaseExpiresAtMs 租约到期时刻（epoch 毫秒）；0 表示不带有效期（永不到期，
 *                         兼容旧式请求）。到期前未结算/未撤销将被系统自动回收。
 * @param version 创建该预留时的账本版本（事件序号）
 */
public record Reservation(String requestId,
                          String sourceId,
                          double requestedPowerW,
                          double grantedPowerW,
                          long durationMs,
                          double reservedWh,
                          ReservationStatus status,
                          long version,
                          long leaseExpiresAtMs) {

    public boolean hasLease() {
        return leaseExpiresAtMs > 0;
    }

    public boolean leaseDue(long nowMs) {
        return hasLease() && nowMs >= leaseExpiresAtMs;
    }

    public Reservation withStatus(ReservationStatus newStatus) {
        return new Reservation(requestId, sourceId, requestedPowerW, grantedPowerW,
                durationMs, reservedWh, newStatus, version, leaseExpiresAtMs);
    }

    public Reservation withLeaseExpiresAt(long newExpiresAtMs) {
        return new Reservation(requestId, sourceId, requestedPowerW, grantedPowerW,
                durationMs, reservedWh, status, version, newExpiresAtMs);
    }
}
