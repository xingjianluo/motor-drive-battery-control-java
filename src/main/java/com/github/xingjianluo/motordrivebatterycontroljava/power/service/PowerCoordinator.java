package com.github.xingjianluo.motordrivebatterycontroljava.power.service;

import com.github.xingjianluo.motordrivebatterycontroljava.power.event.Event;
import com.github.xingjianluo.motordrivebatterycontroljava.power.event.EventLog;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.BatteryModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultReport;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.InverterModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.LedgerSnapshot;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.LimitBound;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.MotorModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.OperationalState;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.RejectionReason;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.Reservation;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.SystemSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 并发入口的总协调器。所有公共方法在同一把锁（this monitor）内执行，
 * 将"仲裁 + 账本变更 + 事件追加 + 电池/电机模型推进"作为一个原子步骤：
 * <ul>
 *   <li>仲裁授予值不超过冻结账本的剩余预算，总线上不存在瞬时超额；</li>
 *   <li>多入口的请求、撤销、结算按同一份账本串行生效，无丢失更新；</li>
 *   <li>每个生效动作先追加事件再改内存态，{@link #replay()} 可复现剩余容量与故障状态；</li>
 *   <li>撤销/结算幂等，杜绝预留泄漏与重复释放。</li>
 * </ul>
 */
public class PowerCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PowerCoordinator.class);
    private static final double EPS = 1e-6;

    private final BatteryModel battery;
    private final InverterModel inverter;
    private final MotorModel motor;
    private final EventLog eventLog;
    private final PowerLedger ledger;
    private final FaultManager faults;
    private final PowerArbiter arbiter;

    /** 每个结算步长的模型时间（秒） */
    private final double stepSeconds;

    public PowerCoordinator(BatteryModel battery,
                            InverterModel inverter,
                            MotorModel motor,
                            EventLog eventLog) {
        this(battery, inverter, motor, eventLog, 1.0);
    }

    public PowerCoordinator(BatteryModel battery,
                            InverterModel inverter,
                            MotorModel motor,
                            EventLog eventLog,
                            double stepSeconds) {
        this.battery = battery;
        this.inverter = inverter;
        this.motor = motor;
        this.eventLog = eventLog;
        this.ledger = new PowerLedger();
        this.faults = new FaultManager();
        this.arbiter = new PowerArbiter(battery, inverter, motor);
        this.stepSeconds = stepSeconds;
        this.ledger.freezeBudget(initialPhysicalBudget());
        log.info("[协调器] 初始化完成：物理预算={}W SOC={} 状态={}",
                ledger.snapshot().totalBudgetWatts(), battery.getSoc(), OperationalState.RUNNING);
    }

    private double initialPhysicalBudget() {
        return Math.min(battery.socPowerLimitW(), inverter.maxOutputPowerW());
    }

    /** 重放事件日志重建账本与故障状态（进程重启恢复） */
    public synchronized void replay() {
        for (Event e : eventLog.readAll()) {
            switch (e) {
                case Event.PowerReserved r -> ledger.reserve(new Reservation(r.requestId(), r.source(), r.watts()));
                case Event.PowerReleased r -> ledger.release(r.requestId());
                case Event.PowerSettled s -> {
                    ledger.settle(s.requestId());
                    battery.consumeEnergyWh(s.energyWh());
                }
                case Event.FaultRaised r -> faults.replayFaultRaised(r.faultKey(), r.type(), r.measuredValue(), r.atNanos());
                case Event.FaultCleared r -> faults.replayFaultCleared(r.faultKey());
                case Event.StateChanged ignored -> {
                    // 状态可由活动故障重算，无需处理
                }
            }
        }
        ledger.refreezeAfterSettle(initialPhysicalBudget());
        OperationalState state = faults.currentState();
        log.info("[协调器] 事件重放完成：状态={} 降级系数={} 账本={} SOC={}",
                state, faults.derateFactor(), ledger.snapshot(), battery.getSoc());
    }

    public synchronized PowerDecision request(PowerRequest request) {
        long now = request.requestedAtNanos() != 0 ? request.requestedAtNanos() : System.nanoTime();
        PowerRequest effective = new PowerRequest(request.requestId(), request.source(),
                request.requestedWatts(), now);

        LedgerSnapshot before = ledger.snapshot();
        OperationalState state = faults.currentState();
        PowerDecision decision = arbiter.arbitrate(effective, state, faults.derateFactor(), before);
        if (!decision.accepted()) {
            return decision;
        }

        Reservation reservation = new Reservation(effective.requestId(), effective.source(), decision.grantedWatts());
        boolean reserved = ledger.reserve(reservation);
        if (!reserved) {
            // 锁内串行且仲裁已确认预算充足，失败只可能是 requestId 重复
            return new PowerDecision(effective.requestId(), false, false, 0.0,
                    RejectionReason.INVALID_REQUEST,
                    LimitBound.NONE,
                    "账本预占失败：requestId 已存在，重复请求不二次占用预算");
        }
        eventLog.append(new Event.PowerReserved(effective.requestId(), effective.source(),
                decision.grantedWatts(), now));
        motor.applyPower(decision.grantedWatts(), stepSeconds);
        log.info("[协调器] 请求受理 requestId={} 授予={}W 账本剩余 {}W -> {}W",
                effective.requestId(), decision.grantedWatts(), before.remainingWatts(),
                ledger.snapshot().remainingWatts());
        return decision;
    }

    /** 撤销预留（幂等）；重复撤销返回 Optional.empty，不产生重复释放 */
    public synchronized Optional<Double> release(String requestId) {
        Optional<Reservation> removed = ledger.release(requestId);
        if (removed.isEmpty()) {
            return Optional.empty();
        }
        double watts = removed.get().watts();
        eventLog.append(new Event.PowerReleased(requestId, watts, System.nanoTime()));
        log.info("[协调器] 撤销完成 requestId={} 释放={}W", requestId, watts);
        return Optional.of(watts);
    }

    /** 结算预留（幂等）：移除预留占用，按实际能量扣减电池容量并重新冻结预算 */
    public synchronized Optional<Double> settle(String requestId, double actualEnergyWh) {
        if (actualEnergyWh < 0 || !Double.isFinite(actualEnergyWh)) {
            throw new IllegalArgumentException("结算能量必须为非负有限值");
        }
        Optional<Reservation> removed = ledger.settle(requestId);
        if (removed.isEmpty()) {
            return Optional.empty();
        }
        double watts = removed.get().watts();
        battery.consumeEnergyWh(actualEnergyWh);
        double newBudget = Math.min(battery.socPowerLimitW(), inverter.maxOutputPowerW());
        ledger.refreezeAfterSettle(newBudget);
        eventLog.append(new Event.PowerSettled(requestId, watts, actualEnergyWh, System.nanoTime()));
        log.info("[协调器] 结算完成 requestId={} 结算功率={}W 实际耗能={}Wh SOC={} 新冻结预算={}W",
                requestId, watts, actualEnergyWh, battery.getSoc(), ledger.snapshot().totalBudgetWatts());
        return Optional.of(watts);
    }

    public synchronized FaultDecision reportFault(FaultReport report) {
        OperationalState before = faults.currentState();
        FaultDecision decision = faults.reportFault(report);
        OperationalState after = faults.currentState();
        if (decision.duplicate()) {
            // 重复上报：不追加事件、不迁移状态、不触碰账本
            return decision;
        }
        boolean raised = faults.activeFaults().containsKey(report.faultKey());
        if (raised) {
            // 新故障先落盘；若组合状态随之迁移，再追加状态迁移流水
            eventLog.append(new Event.FaultRaised(report.faultKey(), report.type(),
                    report.measuredValue(), report.reportedAtNanos()));
            if (before != after) {
                eventLog.append(new Event.StateChanged(before, after,
                        "fault:" + report.type() + ":" + report.faultKey(), report.reportedAtNanos()));
            }
        }
        if (after == OperationalState.STOPPED) {
            motor.coast(stepSeconds);
        }
        return decision;
    }

    public synchronized boolean clearFault(String faultKey, double measuredValue) {
        FaultManager.ClearResult result = faults.clearFault(faultKey, measuredValue);
        if (result.cleared()) {
            long now = System.nanoTime();
            eventLog.append(new Event.FaultCleared(faultKey, result.type(), now));
            if (result.before() != result.after()) {
                eventLog.append(new Event.StateChanged(result.before(), result.after(),
                        "fault-cleared:" + faultKey, now));
            }
        }
        return result.cleared();
    }

    public synchronized SystemSnapshot snapshot() {
        return new SystemSnapshot(faults.currentState(), ledger.snapshot(), faults.activeFaults());
    }
}
