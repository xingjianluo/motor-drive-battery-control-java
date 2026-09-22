package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 功率请求：来自某个控制入口的一次功率预留申请。
 *
 * @param requestId        请求唯一标识（由调用方生成，用于撤销/结算关联）
 * @param source           控制入口标识（如 "rest", "torque-loop"）
 * @param requestedWatts   请求功率（瓦）
 * @param requestedAtNanos 请求时间戳（System.nanoTime 口径，便于流水排序）
 */
public record PowerRequest(String requestId, String source, double requestedWatts, long requestedAtNanos) {
}
