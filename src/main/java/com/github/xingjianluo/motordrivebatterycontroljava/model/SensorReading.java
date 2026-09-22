package com.github.xingjianluo.motordrivebatterycontroljava.model;

/**
 * 传感器采样（电池/逆变器/电机）。
 * valid=false 表示读数无效（传感器故障），系统不得据此继续仲裁。
 */
public record SensorReading(long timestampMs,
                            double batteryVoltageV,
                            double batteryCurrentA,
                            double batteryTempC,
                            double inverterTempC,
                            double motorTempC,
                            double motorCurrentA,
                            boolean valid) {

    public static SensorReading invalid(long timestampMs) {
        return new SensorReading(timestampMs,
                Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, false);
    }
}
