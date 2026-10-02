package com.github.xingjianluo.motordrivebatterycontroljava.model;

/**
 * 功率请求。
 *
 * @param requestId     请求唯一标识（用于幂等与重复提交保护）
 * @param sourceId      控制入口标识（多入口并发区分来源）
 * @param requestedPowerW 请求功率（瓦，正数）
 * @param durationMs    请求持续时间（毫秒），用于折算需冻结的能量
 * @param leaseMs       预留租约有效期（毫秒，可选）；null 或 &lt;=0 表示不带有效期，
 *                      预留会一直挂账直到撤销/结算（与旧版本行为完全兼容）；
 *                      正数表示自受理时刻起 leaseMs 后若未结算/未撤销则自动回收
 */
public record PowerRequest(String requestId,
                           String sourceId,
                           double requestedPowerW,
                           long durationMs,
                           Long leaseMs) {

    /** 不带有效期的兼容构造器。 */
    public PowerRequest(String requestId, String sourceId,
                        double requestedPowerW, long durationMs) {
        this(requestId, sourceId, requestedPowerW, durationMs, null);
    }

    public boolean hasLease() {
        return leaseMs != null && leaseMs > 0;
    }
}
