package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 功率请求被拒绝的原因，须可区分。
 */
public enum RejectionReason {
    /** 请求功率非正数或非法 */
    INVALID_REQUEST,
    /** 系统处于停机状态，不再受理新请求 */
    SYSTEM_STOPPED,
    /** 电池剩余容量（能量）耗尽 */
    BATTERY_DEPLETED,
    /** 电池放电功率预算（剩余容量冻结值）已全部分配 */
    BATTERY_BUDGET_EXHAUSTED
}
