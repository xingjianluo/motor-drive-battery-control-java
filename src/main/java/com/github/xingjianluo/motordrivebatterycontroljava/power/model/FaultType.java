package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 故障类型。每种故障对应固定的处置状态与恢复条件（见 FaultManager）。
 */
public enum FaultType {
    OVER_CURRENT,
    OVER_TEMPERATURE,
    UNDER_VOLTAGE,
    SENSOR_INVALID
}
