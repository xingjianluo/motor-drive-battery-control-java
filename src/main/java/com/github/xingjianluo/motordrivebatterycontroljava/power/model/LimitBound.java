package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 裁剪发生时实际起约束作用的物理边界，用于解释判定依据。
 */
public enum LimitBound {
    /** 未被裁剪 */
    NONE,
    /** 电池荷电状态对应的可用放电功率 */
    BATTERY_SOC_POWER,
    /** 电池最大放电电流折算功率 */
    BATTERY_CURRENT_POWER,
    /** 逆变器最大输出功率 */
    INVERTER_POWER,
    /** 账本剩余可分配功率预算（冻结的剩余容量口径） */
    LEDGER_BUDGET,
    /** 降级模式下的功率折扣 */
    DERATE_FACTOR
}
