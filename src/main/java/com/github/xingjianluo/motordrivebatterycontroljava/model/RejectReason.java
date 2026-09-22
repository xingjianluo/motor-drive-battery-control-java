package com.github.xingjianluo.motordrivebatterycontroljava.model;

/**
 * 拒绝 / 裁剪原因，彼此可区分。
 * ACCEPTED 时集合为空；CLIPPED 时给出触顶的约束；REJECTED 时给出拒绝主因。
 */
public enum RejectReason {
    NONE,
    /** 超过逆变器硬件功率上限（硬限制，直接拒绝，不做裁剪）。 */
    INVERTER_HARDWARE_LIMIT,
    /** 电池剩余可用能量不足以支撑最小可执行功率。 */
    INSUFFICIENT_REMAINING_CAPACITY,
    /** 电池最大放电电流折算功率触顶。 */
    BATTERY_DISCHARGE_CURRENT_LIMIT,
    /** 可执行功率低于最小执行门槛。 */
    BELOW_MIN_EXECUTABLE_POWER,
    /** 系统处于过温降级态，请求超过降级后的可用功率。 */
    SYSTEM_DERATED,
    /** 系统处于停机闭锁态，不受理任何请求。 */
    SYSTEM_SHUTDOWN,
    /** 传感器读数无效，无法安全仲裁。 */
    SENSOR_DATA_INVALID,
    /** 重复的请求 ID（幂等保护）。 */
    DUPLICATE_REQUEST_ID
}
