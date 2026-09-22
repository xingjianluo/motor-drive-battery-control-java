package com.github.xingjianluo.motordrivebatterycontroljava.device;

/**
 * 电机构型（不可变）。
 *
 * @param ratedCurrentA 额定电流（安），超过即过流
 * @param overTempC     电机过温阈值
 * @param minExecutablePowerW 最小可执行功率（瓦）：低于此功率的授予无物理意义，拒绝
 */
public record MotorSpec(double ratedCurrentA,
                        double overTempC,
                        double minExecutablePowerW) {
}
