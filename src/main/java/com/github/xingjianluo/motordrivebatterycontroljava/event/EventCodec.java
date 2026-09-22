package com.github.xingjianluo.motordrivebatterycontroljava.event;

/**
 * 事件日志行编解码。格式：类型 | 序号 | 时间戳 | 负载字段...
 * 管道分隔，NaN 字面量可往返还原无效传感器读数。
 */
public final class EventCodec {

    private EventCodec() { }

    public static DomainEvent parse(String line) {
        String[] f = line.split("\\|", -1);
        long seq = Long.parseLong(f[1]);
        long ts = Long.parseLong(f[2]);
        return switch (f[0]) {
            case "RESERVED" -> ReservationReserved.fromFields(f, seq, ts);
            case "RELEASED" -> ReservationReleased.fromFields(f, seq, ts);
            case "SETTLED" -> ReservationSettled.fromFields(f, seq, ts);
            case "FAULT" -> FaultEvent.fromFields(f, seq, ts);
            default -> throw new IllegalArgumentException("未知事件类型: " + f[0]);
        };
    }
}
