package com.github.xingjianluo.motordrivebatterycontroljava.ledger;

/**
 * 一笔功率预留（账本条目，不可变）。
 *
 * @param version 创建该预留时的账本版本（事件序号）
 */
public record Reservation(String requestId,
                          String sourceId,
                          double requestedPowerW,
                          double grantedPowerW,
                          long durationMs,
                          double reservedWh,
                          ReservationStatus status,
                          long version) {

    public Reservation withStatus(ReservationStatus newStatus) {
        return new Reservation(requestId, sourceId, requestedPowerW, grantedPowerW,
                durationMs, reservedWh, newStatus, version);
    }
}
