package com.github.xingjianluo.motordrivebatterycontroljava.model;

import java.util.List;

/**
 * 功率仲裁结果（不可变）。
 *
 * @param grantedPowerW 实际授予功率；被拒绝时为 0
 * @param bindingLimitW 本次生效的最小约束功率（可解释判定依据）
 * @param ledgerVersion 做出判定时账本版本号
 * @param reasons       原因集合（裁剪/拒绝时可区分具体约束）
 */
public record PowerDecision(String requestId,
                            RequestDecision decision,
                            double requestedPowerW,
                            double grantedPowerW,
                            double bindingLimitW,
                            long ledgerVersion,
                            List<RejectReason> reasons,
                            String message) {

    public boolean granted() {
        return decision != RequestDecision.REJECTED;
    }
}
