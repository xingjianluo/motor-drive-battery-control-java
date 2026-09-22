package com.github.xingjianluo.motordrivebatterycontroljava.fault;

/** 系统聚合故障状态。 */
public enum FaultState {
    /** 正常运行。 */
    RUNNING,
    /** 过温等可降级故障触发的限功率运行。 */
    DERATED,
    /** 严重故障触发的安全停机，闭锁，恢复前不受理功率请求。 */
    SHUTDOWN
}
