package com.github.xingjianluo.motordrivebatterycontroljava.device;

import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;

/**
 * 电池运行时模型：规格 + 实时遥测 + 剩余能量。
 * 仅在协调器持锁内被访问，自身不做并发保护。
 */
public class Battery {

    private final BatterySpec spec;
    private double remainingWh;
    private double voltageV;
    private double currentA;
    private double tempC;
    private boolean sensorValid = true;

    public Battery(BatterySpec spec) {
        this.spec = spec;
        this.remainingWh = spec.capacityWh();
        this.voltageV = spec.nominalVoltageV();
    }

    public BatterySpec spec() { return spec; }
    public double remainingWh() { return remainingWh; }
    public double voltageV() { return voltageV; }
    public double currentA() { return currentA; }
    public double tempC() { return tempC; }
    public boolean sensorValid() { return sensorValid; }

    /** 当前电压下最大放电电流折算的放电功率上限（瓦）：P = U * Imax。 */
    public double maxDischargePowerW() {
        return Math.max(0, voltageV) * spec.maxDischargeCurrentA();
    }

    /** 应用一帧传感器读数；读数无效时仅更新有效标志，不采用其中数值。 */
    public void applyReading(SensorReading reading) {
        sensorValid = reading.valid();
        if (reading.valid()) {
            this.voltageV = reading.batteryVoltageV();
            this.currentA = reading.batteryCurrentA();
            this.tempC = reading.batteryTempC();
        }
    }

    /** 扣减能量（预留结算实扣 / 停机安全储备冻结）。不允许把能量扣成负数。 */
    public void consumeEnergy(double wh) {
        if (wh < 0) {
            throw new IllegalArgumentException("扣减能量不能为负: " + wh);
        }
        this.remainingWh = Math.max(0, remainingWh - wh);
    }

    /** 返还能量（撤销 / 结算差额 / 故障恢复释放安全储备）。 */
    public void refundEnergy(double wh) {
        if (wh < 0) {
            throw new IllegalArgumentException("返还能量不能为负: " + wh);
        }
        this.remainingWh = Math.min(spec.capacityWh(), remainingWh + wh);
    }
}
