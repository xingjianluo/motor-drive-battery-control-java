package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 账本快照：容量账本的一致视图。
 *
 * @param totalBudgetWatts     电池放电功率总预算（冻结口径）
 * @param reservedWatts        已预留功率合计
 * @param remainingWatts       剩余可分配功率
 * @param activeReservations   当前生效预留数
 */
public record LedgerSnapshot(double totalBudgetWatts,
                             double reservedWatts,
                             double remainingWatts,
                             int activeReservations) {
}
