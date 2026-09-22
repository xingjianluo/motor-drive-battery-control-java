package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

import java.util.Map;

/**
 * 系统整体一致快照（状态 + 账本 + 活动故障）。
 */
public record SystemSnapshot(OperationalState state,
                             LedgerSnapshot ledger,
                             Map<String, FaultType> activeFaults) {
}
