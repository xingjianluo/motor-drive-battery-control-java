package com.github.xingjianluo.motordrivebatterycontroljava.fault;

/** 故障类型：过流、过温、欠压、传感器读数无效。 */
public enum FaultType {
    OVER_CURRENT,
    OVER_TEMPERATURE,
    UNDER_VOLTAGE,
    SENSOR_INVALID
}
