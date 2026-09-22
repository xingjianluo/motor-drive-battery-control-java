package com.github.xingjianluo.motordrivebatterycontroljava.device;

/**
 * 电池规格（不可变）。
 *
 * @param capacityWh          总能量（瓦时）
 * @param nominalVoltageV     标称电压（伏）
 * @param maxDischargeCurrentA 最大持续放电电流（安）
 * @param undervoltageV       欠压阈值（伏）
 * @param overTempC           过温阈值（摄氏度）
 * @param recoverTempC        过温恢复阈值（迟滞，低于此值才允许恢复）
 */
public record BatterySpec(double capacityWh,
                          double nominalVoltageV,
                          double maxDischargeCurrentA,
                          double undervoltageV,
                          double overTempC,
                          double recoverTempC) {
}
