package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 功率仲裁结果。
 *
 * @param requestId     对应请求标识
 * @param accepted      是否受理（受理含裁剪后受理）
 * @param clipped       是否发生裁剪
 * @param grantedWatts  实际授予功率（拒绝时为 0）
 * @param rejection     拒绝原因（受理时为 null）
 * @param bindingLimit  起约束作用的物理边界（拒绝时为 null）
 * @param explanation   可解释的判定依据描述
 */
public record PowerDecision(String requestId,
                            boolean accepted,
                            boolean clipped,
                            double grantedWatts,
                            RejectionReason rejection,
                            LimitBound bindingLimit,
                            String explanation) {
}
