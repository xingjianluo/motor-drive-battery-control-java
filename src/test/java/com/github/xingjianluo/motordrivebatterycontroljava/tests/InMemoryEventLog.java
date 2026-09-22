package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import com.github.xingjianluo.motordrivebatterycontroljava.power.event.Event;
import com.github.xingjianluo.motordrivebatterycontroljava.power.event.EventLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 测试用内存事件日志：可模拟进程重启（把日志交给新的协调器重放）。
 */
public class InMemoryEventLog implements EventLog {

    private final List<Event> events = new ArrayList<>();

    @Override
    public synchronized void append(Event event) {
        events.add(event);
    }

    @Override
    public synchronized List<Event> readAll() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized int size() {
        return events.size();
    }
}
