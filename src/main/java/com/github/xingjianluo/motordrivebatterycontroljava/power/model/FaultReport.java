package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 故障上报：由电池/逆变器/电机模型或外部监控产生。
 *
 * @param reportId      上报标识（同一故障的重复上报可使用不同 reportId，按 faultKey 去重）
 * @param faultKey      故障实例键（同一物理故障重复上报时使用相同 faultKey）
 * @param type          故障类型
 * @param measuredValue 触发时的测量值（电流 A / 温度 ℃ / 电压 V，传感器无效时可为 NaN）
 * @param reportedAtNanos 上报时间戳
 */
public record FaultReport(String reportId, String faultKey, FaultType type,
                          double measuredValue, long reportedAtNanos) {
}
