package com.github.xingjianluo.motordrivebatterycontroljava.event;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 进程内事件日志（线程安全由调用方协调器统一持锁；
 * 这里自身也做不可变快照防护，避免被外部绕过）。
 */
public class InMemoryEventStore implements EventStore {

    private final List<DomainEvent> events = new ArrayList<>();

    @Override
    public void append(DomainEvent event) {
        events.add(event);
    }

    @Override
    public List<DomainEvent> loadAll() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    @Override
    public long lastSequence() {
        return events.isEmpty() ? 0L : events.get(events.size() - 1).sequence();
    }
}
