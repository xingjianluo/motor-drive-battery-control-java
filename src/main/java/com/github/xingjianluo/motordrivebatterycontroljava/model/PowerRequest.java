package com.github.xingjianluo.motordrivebatterycontroljava.model;

/**
 * 功率请求。
 *
 * @param requestId     请求唯一标识（用于幂等与重复提交保护）
 * @param sourceId      控制入口标识（多入口并发区分来源）
 * @param requestedPowerW 请求功率（瓦，正数）
 * @param durationMs    请求持续时间（毫秒），用于折算需冻结的能量
 * @param leaseTtlMs    预留租约有效期（毫秒，可空）：受理后若在该期限内既未结算也未撤销，
 *                      系统自动回收预留、释放冻结容量。null 或 <=0 表示不带有效期
 *                      （兼容旧式请求，永不自动回收）。
 */
public record PowerRequest(String requestId, String sourceId,
                           double requestedPowerW, long durationMs,
                           Long leaseTtlMs) {

    /** 兼容旧式四参构造（不带租约）。 */
    public PowerRequest(String requestId, String sourceId,
                        double requestedPowerW, long durationMs) {
        this(requestId, sourceId, requestedPowerW, durationMs, null);
    }

    /** 归一化：null 或负数视为不带有效期。 */
    public long effectiveLeaseTtlMs() {
        return leaseTtlMs == null || leaseTtlMs <= 0 ? 0 : leaseTtlMs;
    }
}
