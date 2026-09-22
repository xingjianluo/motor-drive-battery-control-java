package com.github.xingjianluo.motordrivebatterycontroljava.power.service;

import com.github.xingjianluo.motordrivebatterycontroljava.power.model.BatteryModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.InverterModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.LedgerSnapshot;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.LimitBound;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.MotorModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.OperationalState;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.RejectionReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 纯函数式功率仲裁器（不持有可变状态）。
 * <p>裁定顺序（短路、原因可区分）：
 * <ol>
 *   <li>请求非法（非正/非有限值） -> 拒绝 INVALID_REQUEST</li>
 *   <li>系统 STOPPED -> 拒绝 SYSTEM_STOPPED</li>
 *   <li>电池 SOC=0，剩余容量耗尽 -> 拒绝 BATTERY_DEPLETED</li>
 *   <li>账本剩余可分配预算为 0 -> 拒绝 BATTERY_BUDGET_EXHAUSTED</li>
 *   <li>否则授予 min(请求, 账本剩余, 物理上限×降级系数)；
 *       授予 &lt; 请求即裁剪，并标注真正起作用的约束边界。</li>
 * </ol>
 * 物理上限 = min(SOC 放电功率, 放电电流折算功率, 逆变器最大功率)。
 */
public class PowerArbiter {

    private static final Logger log = LoggerFactory.getLogger(PowerArbiter.class);
    private static final double EPS = 1e-6;

    private final BatteryModel battery;
    private final InverterModel inverter;

    public PowerArbiter(BatteryModel battery, InverterModel inverter, MotorModel motor) {
        this.battery = battery;
        this.inverter = inverter;
        // motor 当前不参与静态功率上限（温度通过故障链影响降级系数），保留入参以便扩展
    }

    public PowerDecision arbitrate(PowerRequest request,
                                   OperationalState state,
                                   double derateFactor,
                                   LedgerSnapshot ledger) {
        double requested = request.requestedWatts();

        if (!Double.isFinite(requested) || requested <= 0.0) {
            return reject(request, RejectionReason.INVALID_REQUEST, LimitBound.NONE,
                    String.format("请求功率非法 requested=%sW（须为正有限值）", requested));
        }
        if (state == OperationalState.STOPPED) {
            return reject(request, RejectionReason.SYSTEM_STOPPED, LimitBound.NONE,
                    "系统处于 STOPPED 停机状态，不受理新功率请求");
        }
        if (battery.getSoc() <= EPS) {
            return reject(request, RejectionReason.BATTERY_DEPLETED, LimitBound.NONE,
                    "电池剩余容量耗尽 SOC=0");
        }
        if (ledger.remainingWatts() <= EPS) {
            return reject(request, RejectionReason.BATTERY_BUDGET_EXHAUSTED, LimitBound.LEDGER_BUDGET,
                    String.format("冻结账本剩余预算=0W（已预留 %.2fW / 总预算 %.2fW），全部容量已冻结分配",
                            ledger.reservedWatts(), ledger.totalBudgetWatts()));
        }

        double socLimit = battery.socPowerLimitW();
        double currentLimit = battery.currentPowerLimitW();
        double inverterLimit = inverter.maxOutputPowerW();

        double physicalCap = Double.POSITIVE_INFINITY;
        LimitBound physicalBound = LimitBound.NONE;
        double[] candidates = {socLimit, currentLimit, inverterLimit};
        LimitBound[] bounds = {
                LimitBound.BATTERY_SOC_POWER, LimitBound.BATTERY_CURRENT_POWER, LimitBound.INVERTER_POWER};
        for (int i = 0; i < candidates.length; i++) {
            if (candidates[i] < physicalCap) {
                physicalCap = candidates[i];
                physicalBound = bounds[i];
            }
        }

        double deratedCap = physicalCap * derateFactor;
        double budget = ledger.remainingWatts();
        double granted = Math.min(requested, Math.min(budget, deratedCap));

        boolean clipped = granted + EPS < requested;
        LimitBound binding = LimitBound.NONE;
        if (clipped) {
            if (budget + EPS < deratedCap) {
                // 账本冻结剩余先于物理上限成为短板（通常是已有其他预留占用）
                binding = LimitBound.LEDGER_BUDGET;
            } else if (derateFactor < 1.0 - EPS) {
                // 打平时物理约束优先（冻结预算本就来自物理上限）；有降级时真正的短板是降级系数
                binding = LimitBound.DERATE_FACTOR;
            } else {
                binding = physicalBound;
            }
        }

        String explanation = String.format(
                "输入[requestId=%s source=%s 请求=%.2fW state=%s 降级系数=%.2f SOC=%.3f] "
                        + "边界[SOC功率=%.2fW 电流功率=%.2fW 逆变器=%.2fW] "
                        + "账本[剩余=%.2fW 已预留=%.2fW / 总预算=%.2fW] "
                        + "裁定=%.2fW%s 起作用约束=%s",
                request.requestId(), request.source(), requested, state, derateFactor, battery.getSoc(),
                socLimit, currentLimit, inverterLimit,
                budget, ledger.reservedWatts(), ledger.totalBudgetWatts(),
                granted, clipped ? "（裁剪）" : "（足额）", binding);

        log.info("[仲裁] {}", explanation);
        return new PowerDecision(request.requestId(), true, clipped, granted, null, binding, explanation);
    }

    private PowerDecision reject(PowerRequest request, RejectionReason reason,
                                 LimitBound bound, String explanation) {
        String full = String.format("输入[requestId=%s source=%s 请求=%sW] 拒绝原因=%s 依据：%s",
                request.requestId(), request.source(), request.requestedWatts(), reason, explanation);
        log.info("[仲裁] {}", full);
        return new PowerDecision(request.requestId(), false, false, 0.0, reason, bound, full);
    }
}
