package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 进程内逆变器模型：提供逆变器侧功率约束。
 */
public class InverterModel {

    private final double maxOutputPowerW;

    public InverterModel(double maxOutputPowerW) {
        this.maxOutputPowerW = maxOutputPowerW;
    }

    public double maxOutputPowerW() {
        return maxOutputPowerW;
    }
}
