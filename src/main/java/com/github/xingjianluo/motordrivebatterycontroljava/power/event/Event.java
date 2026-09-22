package com.github.xingjianluo.motordrivebatterycontroljava.power.event;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.OperationalState;

/**
 * 可持久化、可重放的账本/状态事件。所有改变账本或故障状态的动作都先记录事件再生效，
 * 进程重启后按序重放即可复现剩余容量与故障状态。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Event.PowerReserved.class, name = "powerReserved"),
        @JsonSubTypes.Type(value = Event.PowerReleased.class, name = "powerReleased"),
        @JsonSubTypes.Type(value = Event.PowerSettled.class, name = "powerSettled"),
        @JsonSubTypes.Type(value = Event.FaultRaised.class, name = "faultRaised"),
        @JsonSubTypes.Type(value = Event.FaultCleared.class, name = "faultCleared"),
        @JsonSubTypes.Type(value = Event.StateChanged.class, name = "stateChanged")
})
public sealed interface Event {

    /** 功率预留生效 */
    record PowerReserved(String requestId, String source, double watts, long atNanos) implements Event {
    }

    /** 功率预留撤销（释放，不扣能量） */
    record PowerReleased(String requestId, double watts, long atNanos) implements Event {
    }

    /** 功率预留结算（按实际消耗从剩余容量中扣减） */
    record PowerSettled(String requestId, double watts, double energyWh, long atNanos) implements Event {
    }

    /** 故障触发（同一 faultKey 只记录一次） */
    record FaultRaised(String faultKey, FaultType type, double measuredValue, long atNanos) implements Event {
    }

    /** 故障恢复（须满足恢复条件后显式上报） */
    record FaultCleared(String faultKey, FaultType type, long atNanos) implements Event {
    }

    /** 运行状态迁移流水 */
    record StateChanged(OperationalState from, OperationalState to, String reason, long atNanos) implements Event {
    }
}
