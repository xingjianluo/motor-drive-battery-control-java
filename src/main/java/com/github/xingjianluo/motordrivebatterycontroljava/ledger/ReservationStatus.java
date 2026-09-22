package com.github.xingjianluo.motordrivebatterycontroljava.ledger;

/** 预留生命周期状态。 */
public enum ReservationStatus {
    /** 已预留（能量已冻结）。 */
    RESERVED,
    /** 已撤销（冻结能量全部返还，终止态）。 */
    RELEASED,
    /** 已结算（按实际耗能扣减，差额返还，终止态）。 */
    SETTLED
}
