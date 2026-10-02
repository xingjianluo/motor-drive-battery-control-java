package com.github.xingjianluo.motordrivebatterycontroljava.event;


/**
 * 领域事件：进入同一条持久化日志，重放后可同时重建账本与故障状态。
 * 所有事件共享全局单调序号与时间戳。
 */
public sealed interface DomainEvent
        permits ReservationReserved, ReservationReleased, ReservationSettled,
                ReservationRenewed, ReservationExpired, FaultEvent {

    long sequence();

    long timestampMs();

    /** 序列化为单行文本（JSON-lines 风格，管道分隔）。 */
    String toLogLine();
}
