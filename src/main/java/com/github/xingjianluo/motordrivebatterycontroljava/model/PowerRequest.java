package com.github.xingjianluo.motordrivebatterycontroljava.model;

/**
 * 功率请求。
 *
 * @param requestId     请求唯一标识（用于幂等与重复提交保护）
 * @param sourceId      控制入口标识（多入口并发区分来源）
 * @param requestedPowerW 请求功率（瓦，正数）
 * @param durationMs    请求持续时间（毫秒），用于折算需冻结的能量
 */
public record PowerRequest(String requestId, String sourceId,
                           double requestedPowerW, long durationMs) {
}
