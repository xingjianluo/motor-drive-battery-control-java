package com.github.xingjianluo.motordrivebatterycontroljava.event;

import java.util.List;

/**
 * 事件日志：append + 全量读取。
 * 内存实现用于测试/默认；文件实现用于进程重启后重放。
 */
public interface EventStore extends AutoCloseable {

    void append(DomainEvent event);

    List<DomainEvent> loadAll();

    long lastSequence();

    @Override
    default void close() { }
}
