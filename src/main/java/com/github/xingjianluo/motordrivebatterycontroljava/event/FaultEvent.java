package com.github.xingjianluo.motordrivebatterycontroljava.event;

import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;

/**
 * 故障上报 / 消除事件（持久化/重放的基本单位）。
 *
 * @param active true=故障触发上报；false=故障消除上报
 * @param reading 触发时读数（可空）；序列化时 NaN 记为 NaN 字面量
 */
public record FaultEvent(long sequence,
                         long timestampMs,
                         FaultType type,
                         boolean active,
                         SensorReading reading) implements DomainEvent {

    @Override
    public String toLogLine() {
        if (reading == null) {
            return String.join("|", "FAULT", Long.toString(sequence),
                    Long.toString(timestampMs), type.name(),
                    Boolean.toString(active), "NULL");
        }
        return String.join("|", "FAULT", Long.toString(sequence),
                Long.toString(timestampMs), type.name(),
                Boolean.toString(active),
                Double.toString(reading.batteryVoltageV()),
                Double.toString(reading.batteryCurrentA()),
                Double.toString(reading.batteryTempC()),
                Double.toString(reading.inverterTempC()),
                Double.toString(reading.motorTempC()),
                Double.toString(reading.motorCurrentA()),
                Boolean.toString(reading.valid()));
    }

    public static FaultEvent fromFields(String[] f, long seq, long ts) {
        FaultType type = FaultType.valueOf(f[3]);
        boolean active = Boolean.parseBoolean(f[4]);
        SensorReading reading = null;
        if (!"NULL".equals(f[5])) {
            reading = new SensorReading(ts,
                    Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                    Double.parseDouble(f[7]), Double.parseDouble(f[8]),
                    Double.parseDouble(f[9]), Double.parseDouble(f[10]),
                    Boolean.parseBoolean(f[11]));
        }
        return new FaultEvent(seq, ts, type, active, reading);
    }
}
