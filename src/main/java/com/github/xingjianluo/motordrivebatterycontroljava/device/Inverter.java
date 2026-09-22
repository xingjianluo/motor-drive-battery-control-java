package com.github.xingjianluo.motordrivebatterycontroljava.device;

import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;

/** 逆变器运行时模型：额定功率为硬上限，温度参与过温判定。 */
public class Inverter {

    private final InverterSpec spec;
    private double tempC;
    private boolean sensorValid = true;

    public Inverter(InverterSpec spec) {
        this.spec = spec;
    }

    public InverterSpec spec() { return spec; }
    public double tempC() { return tempC; }
    public boolean sensorValid() { return sensorValid; }

    public void applyReading(SensorReading reading) {
        sensorValid = reading.valid();
        if (reading.valid()) {
            this.tempC = reading.inverterTempC();
        }
    }
}
