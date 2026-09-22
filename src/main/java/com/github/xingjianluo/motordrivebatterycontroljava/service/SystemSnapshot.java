package com.github.xingjianluo.motordrivebatterycontroljava.service;

import java.util.List;
import java.util.Map;

import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultState;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.Reservation;

/**
 * 系统可观测快照（查询接口返回，不可变）。
 * 能量口径（守恒：remainingWh = availableWh + frozenWh + safetyReserveWh）：
 * - remainingWh：电池物理剩余（含已冻结与安全储备）
 * - frozenWh：活动预留冻结的能量
 * - safetyReserveWh：停机闭锁冻结的安全储备
 * - availableWh：可调度（未冻结）能量
 */
public record SystemSnapshot(long timestampMs,
                             FaultState faultState,
                             double remainingWh,
                             double frozenWh,
                             double availableWh,
                             double safetyReserveWh,
                             double batteryVoltageV,
                             double batteryCurrentA,
                             double batteryTempC,
                             double inverterTempC,
                             double motorTempC,
                             double motorCurrentA,
                             boolean sensorValid,
                             long ledgerVersion,
                             long eventCount,
                             Map<FaultType, Boolean> activeFaults,
                             List<Reservation> activeReservations) {
}
