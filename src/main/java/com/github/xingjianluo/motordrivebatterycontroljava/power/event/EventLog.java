package com.github.xingjianluo.motordrivebatterycontroljava.power.event;

import java.util.List;

/**
 * 事件日志：追加写 + 全量读取（用于重启重放）。
 */
public interface EventLog {

    void append(Event event);

    List<Event> readAll();
}
