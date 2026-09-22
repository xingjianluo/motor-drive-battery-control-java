package com.github.xingjianluo.motordrivebatterycontroljava.device;

/**
 * 逆变器规格（不可变）。
 *
 * @param ratedPowerW   额定可输出功率（瓦）——超过此值的请求一律拒绝
 * @param overTempC     过温阈值
 * @param recoverTempC  过温恢复阈值（迟滞）
 */
public record InverterSpec(double ratedPowerW,
                           double overTempC,
                           double recoverTempC) {
}
