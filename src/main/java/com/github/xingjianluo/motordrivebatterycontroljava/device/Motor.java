package com.github.xingjianluo.motordrivebatterycontroljava.device;

import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;

/** 电机运行时模型：电流参与过流判定，温度参与过温判定。 */
public class Motor {

    private final MotorSpec spec;
    private double currentA;
    private double tempC;
    private boolean sensorValid = true;

    public Motor(MotorSpec spec) {
        this.spec = spec;
    }

    public MotorSpec spec() { return spec; }
    public double currentA() { return currentA; }
    public double tempC() { return tempC; }
    public boolean sensorValid() { return sensorValid; }

    public void applyReading(SensorReading reading) {
        sensorValid = reading.valid();
        if (reading.valid()) {
            this.currentA = reading.motorCurrentA();
            this.tempC = reading.motorTempC();
        }
    }
}
