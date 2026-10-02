package com.github.xingjianluo.motordrivebatterycontroljava.ledger;

/**
 * 一笔功率预留（账本条目，不可变）。
 *
 * @param version   创建该预留时的账本版本（事件序号）
 * @param expiresAtEpochMs 租约到期时刻；{@link #NEVER_EXPIRES} 表示不带有效期（永不过期）
 */
public record Reservation(String requestId,
                          String sourceId,
                          double requestedPowerW,
                          double grantedPowerW,
                          long durationMs,
                          double reservedWh,
                          ReservationStatus status,
                          long version,
                          long expiresAtEpochMs) {

    /** 不带有效期的预留：租约无限长，行为与旧版本完全一致。 */
    public static final long NEVER_EXPIRES = Long.MAX_VALUE;

    public Reservation withStatus(ReservationStatus newStatus) {
        return new Reservation(requestId, sourceId, requestedPowerW, grantedPowerW,
                durationMs, reservedWh, newStatus, version, expiresAtEpochMs);
    }

    /** 续期后返回新的租约到期时刻（其余字段不变）。 */
    public Reservation withExpiry(long newExpiresAtEpochMs) {
        return new Reservation(requestId, sourceId, requestedPowerW, grantedPowerW,
                durationMs, reservedWh, status, version, newExpiresAtEpochMs);
    }

    /** 该预留是否带有有效期。 */
    public boolean hasLease() {
        return expiresAtEpochMs != NEVER_EXPIRES;
    }

    /** 在给定时刻租约是否已到期。 */
    public boolean isExpiredAt(long nowMs) {
        return hasLease() && nowMs >= expiresAtEpochMs;
    }
}
