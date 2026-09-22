package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 进程内电机模型：按授予功率推进转速与温度。
 * <ul>
 *   <li>转速目标与功率占空比（相对逆变器最大功率）成正比，一阶惯性逼近；</li>
 *   <li>温度按功率发热、向环境一阶冷却，供过温故障判定使用。</li>
 * </ul>
 */
public class MotorModel {

    private static final double RPM_TIME_CONSTANT_S = 0.5;
    private static final double THERMAL_TIME_CONSTANT_S = 60.0;
    /** 额定功率下的稳态温升（℃） */
    private static final double FULL_POWER_TEMP_RISE_C = 120.0;
    private static final double AMBIENT_C = 25.0;

    private final double maxRpm;
    private final double ratedPowerW;
    private double currentRpm;
    private double temperatureC;

    public MotorModel(double maxRpm, double initialTemperatureC, double ratedPowerW) {
        if (maxRpm <= 0 || ratedPowerW <= 0) {
            throw new IllegalArgumentException("电机参数必须为正数");
        }
        this.maxRpm = maxRpm;
        this.ratedPowerW = ratedPowerW;
        this.currentRpm = 0.0;
        this.temperatureC = initialTemperatureC;
    }

    /** 按授予功率推进一个步长 */
    public void applyPower(double watts, double dtSeconds) {
        if (dtSeconds < 0) {
            throw new IllegalArgumentException("时间步长不能为负");
        }
        double fraction = Math.min(1.0, Math.max(0.0, watts / ratedPowerW));
        double targetRpm = maxRpm * fraction;
        double alpha = 1.0 - Math.exp(-dtSeconds / RPM_TIME_CONSTANT_S);
        currentRpm += (targetRpm - currentRpm) * alpha;

        double steadyTemp = AMBIENT_C + FULL_POWER_TEMP_RISE_C * fraction;
        double beta = 1.0 - Math.exp(-dtSeconds / THERMAL_TIME_CONSTANT_S);
        temperatureC += (steadyTemp - temperatureC) * beta;
    }

    /** 停机/空载：转速衰减、温度回落到环境 */
    public void coast(double dtSeconds) {
        applyPower(0.0, dtSeconds);
    }

    public double getCurrentRpm() {
        return currentRpm;
    }

    public double getTemperatureC() {
        return temperatureC;
    }

    public void setTemperatureC(double temperatureC) {
        this.temperatureC = temperatureC;
    }

    public double getMaxRpm() {
        return maxRpm;
    }

    public double getRatedPowerW() {
        return ratedPowerW;
    }
}
