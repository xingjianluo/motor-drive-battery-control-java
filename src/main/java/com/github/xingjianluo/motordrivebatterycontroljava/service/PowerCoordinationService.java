package com.github.xingjianluo.motordrivebatterycontroljava.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;

import com.github.xingjianluo.motordrivebatterycontroljava.device.Battery;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Inverter;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Motor;
import com.github.xingjianluo.motordrivebatterycontroljava.event.DomainEvent;
import com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.FaultEvent;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationExpired;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationReleased;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationRenewed;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationReserved;
import com.github.xingjianluo.motordrivebatterycontroljava.event.ReservationSettled;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultManager;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultState;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.PowerLedger;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.Reservation;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.ReservationStatus;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RejectReason;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RequestDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;
import com.github.xingjianluo.motordrivebatterycontroljava.time.SystemTimeSource;
import com.github.xingjianluo.motordrivebatterycontroljava.time.TimeSource;

/**
 * 电驱动与电池功率协同控制协调器（核心入口，线程安全）。
 *
 * 单一内置锁串行化：传感器刷新、故障上报、功率受理、撤销、结算、续期、租约回收与查询，
 * 保证并发入口下使用同一份账本与同一份"冻结容量快照"做判定，
 * 裁剪结果与冻结剩余容量严格一致，总线上不出现瞬时超额。
 *
 * 能量账本（Wh）口径（守恒：physical remaining = available + frozen + safetyReserve）：
 *   remainingWh（battery）：电池物理剩余，只在结算实耗时减少
 *   frozenWh：活动预留冻结的能量（预留时登记，撤销/过期回收/停机时释放，结算时转实耗）
 *   safetyReserveWh：停机闭锁冻结的安全储备（闭锁登记，恢复释放）
 *   可调度能量 = remainingWh - frozenWh - safetyReserveWh
 */
public class PowerCoordinationService {

    private static final Logger log = LoggerFactory.getLogger(PowerCoordinationService.class);

    private final Battery battery;
    private final Inverter inverter;
    private final Motor motor;
    private final PowerLedger ledger;
    private final FaultManager faultManager;
    private final EventStore eventStore;
    private final TimeSource timeSource;
    private ScheduledExecutorService reaper;
    private final Object lock = new Object();

    /** 兼容旧调用：墙钟时间源、不启动后台租约回收器（回收仍可由任一入口触发）。 */
    public PowerCoordinationService(Battery battery, Inverter inverter, Motor motor,
                                    EventStore eventStore,
                                    double overTempDerateFactor,
                                    double shutdownReserveWh) {
        this(battery, inverter, motor, eventStore, new SystemTimeSource(),
                overTempDerateFactor, shutdownReserveWh, 0);
    }

    /**
     * @param leaseReaperIntervalMs 后台自动回收周期；&lt;=0 表示不启动后台线程，
     *                              过期回收仍会在每次请求/撤销/结算/续期/查询入口先执行
     */
    public PowerCoordinationService(Battery battery, Inverter inverter, Motor motor,
                                    EventStore eventStore, TimeSource timeSource,
                                    double overTempDerateFactor,
                                    double shutdownReserveWh,
                                    long leaseReaperIntervalMs) {
        this.battery = battery;
        this.inverter = inverter;
        this.motor = motor;
        this.eventStore = eventStore;
        this.timeSource = timeSource;
        this.ledger = new PowerLedger(eventStore, timeSource);
        this.faultManager = new FaultManager(eventStore, battery, inverter, motor,
                overTempDerateFactor, shutdownReserveWh);
        replay();
        if (leaseReaperIntervalMs > 0) {
            this.reaper = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "lease-reaper");
                t.setDaemon(true);
                return t;
            });
            reaper.scheduleAtFixedRate(this::reapExpiredReservations,
                    leaseReaperIntervalMs, leaseReaperIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * 在锁内回收所有已到期未结算/未撤销的预留（与请求/撤销/结算共用同一账本口径）。
     * 每个公共入口都会先调用本方法，保证任何时间交叠下都不会长期挂账或重复释放。
     *
     * @return 本次实际回收的预留
     */
    public List<Reservation> reapExpiredReservations() {
        synchronized (lock) {
            long now = timeSource.nowMs();
            List<ReservationExpired> events = ledger.expireDue(now);
            for (ReservationExpired e : events) {
                log.info("[租约] 自动回收 id={} 来源={} 返还冻结={}Wh 到期时刻={} 回收时刻={} 可调度={}Wh",
                        e.requestId(), e.sourceId(), fmt(e.refundedWh()),
                        e.expiresAtEpochMs(), now,
                        fmt(battery.remainingWh() - ledger.frozenWh()
                                - faultManager.safetyReserveWh()));
            }
            return events.stream()
                    .map(e -> ledger.find(e.requestId()).orElseThrow())
                    .toList();
        }
    }

    /** 停止后台回收器（无后台回收器时为空操作）。 */
    @PreDestroy
    public void shutdown() {
        ScheduledExecutorService r = reaper;
        if (r != null) {
            r.shutdownNow();
        }
    }

    /** 重放事件日志，重建账本与故障状态（进程重启/重放后可复现）。 */
    private void replay() {
        List<DomainEvent> events = eventStore.loadAll();
        for (DomainEvent e : events) {
            switch (e) {
                case ReservationReserved r -> ledger.applyEvent(r);
                case ReservationReleased r -> ledger.applyEvent(r);
                case ReservationSettled r -> {
                    ledger.applyEvent(r);
                    // 物理剩余只在真正耗能（结算）时减少
                    battery.consumeEnergy(r.actualConsumedWh());
                }
                // 续期只改到期时刻、过期回收只释放冻结，均不动物理剩余
                case ReservationRenewed r -> ledger.applyEvent(r);
                case ReservationExpired r -> ledger.applyEvent(r);
                case FaultEvent f -> {
                    if (f.reading() != null) {
                        battery.applyReading(f.reading());
                        inverter.applyReading(f.reading());
                        motor.applyReading(f.reading());
                    }
                    faultManager.applyEventReplay(f);
                }
            }
        }
        log.info("[重放] 完成，事件数={} 故障状态={} 剩余能量={}Wh 冻结={}Wh",
                events.size(), faultManager.state(),
                fmt(battery.remainingWh()), fmt(ledger.frozenWh()));
    }

    /** 接入一帧传感器读数：刷新设备模型并自动评估阈值故障。返回新闭锁的故障类型。 */
    public List<FaultType> ingestReading(SensorReading reading) {
        synchronized (lock) {
            log.info("[传感] 输入 t={} 有效={} U={}V I={}A Tb={}℃ Ti={}℃ Tm={}℃ Im={}A",
                    reading.timestampMs(), reading.valid(),
                    fmt(reading.batteryVoltageV()), fmt(reading.batteryCurrentA()),
                    fmt(reading.batteryTempC()), fmt(reading.inverterTempC()),
                    fmt(reading.motorTempC()), fmt(reading.motorCurrentA()));
            battery.applyReading(reading);
            inverter.applyReading(reading);
            motor.applyReading(reading);

            // 任何入口活动前先回收到期租约，冻结容量即时归还
            reapExpiredInLock();
            List<FaultType> detected = evaluateFaults(reading);
            List<FaultType> newlyLatched = new ArrayList<>();
            for (FaultType type : detected) {
                if (!faultManager.isFaultActive(type)) {
                    applyReported(type, true, reading);
                    newlyLatched.add(type);
                }
            }
            return newlyLatched;
        }
    }

    /** 依据读数评估当前越过阈值的故障集合（不改变状态，只产出候选）。 */
    private List<FaultType> evaluateFaults(SensorReading r) {
        List<FaultType> faults = new ArrayList<>();
        if (!r.valid()) {
            faults.add(FaultType.SENSOR_INVALID);
            return faults;
        }
        if (r.motorCurrentA() > motor.spec().ratedCurrentA()) {
            faults.add(FaultType.OVER_CURRENT);
        }
        if (r.batteryVoltageV() < battery.spec().undervoltageV()) {
            faults.add(FaultType.UNDER_VOLTAGE);
        }
        boolean overTemp = r.batteryTempC() > battery.spec().overTempC()
                || r.inverterTempC() > inverter.spec().overTempC()
                || r.motorTempC() > motor.spec().overTempC();
        if (overTemp) {
            faults.add(FaultType.OVER_TEMPERATURE);
        }
        return faults;
    }

    public FaultManager.Transition reportFault(FaultType type, SensorReading reading) {
        synchronized (lock) {
            return applyReported(type, true, reading);
        }
    }

    public FaultManager.Transition clearFault(FaultType type, SensorReading reading) {
        synchronized (lock) {
            return applyReported(type, false, reading);
        }
    }

    /**
     * 故障统一出口：状态机迁移（安全储备是冻结账本字段，不动物理剩余）+
     * 停机时释放全部活动预留（避免预留泄漏，总线立即零输出）。
     */
    private FaultManager.Transition applyReported(FaultType type, boolean active,
                                                  SensorReading reading) {
        reapExpiredInLock();
        FaultState beforeState = faultManager.state();
        FaultManager.Transition t = faultManager.report(type, active, reading);
        if (beforeState != FaultState.SHUTDOWN
                && faultManager.state() == FaultState.SHUTDOWN) {
            for (Reservation r : List.copyOf(ledger.activeReservations())) {
                ledger.release(r.requestId(), r.sourceId());
            }
        }
        return t;
    }

    public FaultState faultState() {
        synchronized (lock) { return faultManager.state(); }
    }

    /**
     * 受理功率请求并预留：
     * 1) 停机/传感器无效 -> 拒绝；
     * 2) 超过逆变器额定功率（硬上限）-> 拒绝，不裁剪；
     * 3) 取各约束（放电电流、剩余可调度能量、过温降级）最小值为 bindingLimit；
     * 4) 授予 = min(请求, bindingLimit)；低于最小可执行功率 -> 拒绝；
     * 5) 等于请求=ACCEPTED，小于=CLIPPED；授予功率立即冻结对应能量，保证总线上不瞬时超额。
     */
    public PowerDecision request(PowerRequest req) {
        synchronized (lock) {
            // 任何新受理前先回收已到期预留，冻结容量即时归还可调度池
            reapExpiredInLock();
            long version = ledger.version();
            long now = timeSource.nowMs();
            long expiresAt = req.hasLease()
                    ? saturatedAdd(now, req.leaseMs())
                    : Reservation.NEVER_EXPIRES;
            double requested = req.requestedPowerW();
            log.info("[请求] 输入 id={} 来源={} 请求={}W 时长={}ms 租约={}ms 到期={} 版本={} 状态={}",
                    req.requestId(), req.sourceId(), fmt(requested),
                    req.durationMs(), req.hasLease() ? req.leaseMs() : -1,
                    req.hasLease() ? expiresAt : "NEVER", version, faultManager.state());

            if (ledger.exists(req.requestId())) {
                return reject(req, RejectReason.DUPLICATE_REQUEST_ID,
                        "重复的 requestId: " + req.requestId(), 0, version);
            }
            if (faultManager.state() == FaultState.SHUTDOWN) {
                return reject(req, RejectReason.SYSTEM_SHUTDOWN,
                        "系统处于停机闭锁态，拒绝受理", 0, version);
            }
            if (!battery.sensorValid() || !inverter.sensorValid() || !motor.sensorValid()) {
                return reject(req, RejectReason.SENSOR_DATA_INVALID,
                        "传感器读数无效，无法安全仲裁", 0, version);
            }
            if (requested <= 0 || req.durationMs() <= 0) {
                return reject(req, RejectReason.BELOW_MIN_EXECUTABLE_POWER,
                        "请求功率与时长必须为正", 0, version);
            }

            double inverterHard = inverter.spec().ratedPowerW();
            if (requested > inverterHard) {
                return reject(req, RejectReason.INVERTER_HARDWARE_LIMIT,
                        "请求 " + fmt(requested) + "W 超过逆变器硬上限 " + fmt(inverterHard) + "W",
                        inverterHard, version);
            }

            // ---- 在同一份冻结容量快照上计算各约束（锁内，不会被并发入口改写） ----
            // 口径：物理剩余 remainingWh 只在结算实耗时减少；可调度 = 物理剩余 - 冻结 - 安全储备
            double schedulableWh = battery.remainingWh() - ledger.frozenWh()
                    - faultManager.safetyReserveWh();
            double capacityPowerW = schedulableWh * 3_600_000.0 / Math.max(1, req.durationMs());
            double currentPowerW = battery.maxDischargePowerW();
            double faultFactor = faultManager.powerFactor();

            Set<RejectReason> binders = new LinkedHashSet<>();
            double binding = inverterHard;
            if (currentPowerW < binding) { binding = currentPowerW; }
            if (capacityPowerW < binding) { binding = capacityPowerW; }
            double effectiveBinding = binding * faultFactor;
            if (faultFactor < 1.0) { binders.add(RejectReason.SYSTEM_DERATED); }
            if (currentPowerW <= effectiveBinding + 1e-9) {
                binders.add(RejectReason.BATTERY_DISCHARGE_CURRENT_LIMIT);
            }
            if (capacityPowerW <= effectiveBinding + 1e-9) {
                binders.add(RejectReason.INSUFFICIENT_REMAINING_CAPACITY);
            }

            double granted = Math.min(requested, Math.max(0, effectiveBinding));
            log.info("[仲裁] 依据: 可调度={}Wh 容量折算={}W 电流折算={}W 逆变器={}W 降级系数={} -> 绑定上限={}W",
                    fmt(schedulableWh), fmt(capacityPowerW), fmt(currentPowerW),
                    fmt(inverterHard), fmt(faultFactor), fmt(effectiveBinding));

            if (granted < motor.spec().minExecutablePowerW()) {
                if (binders.isEmpty()) {
                    binders.add(RejectReason.INSUFFICIENT_REMAINING_CAPACITY);
                }
                RejectReason main = capacityPowerW <= currentPowerW
                        ? RejectReason.INSUFFICIENT_REMAINING_CAPACITY
                        : RejectReason.BATTERY_DISCHARGE_CURRENT_LIMIT;
                return new PowerDecision(req.requestId(), RequestDecision.REJECTED,
                        requested, 0, effectiveBinding, version, List.copyOf(binders),
                        "可执行功率 " + fmt(granted) + "W 低于最小执行门槛 "
                                + motor.spec().minExecutablePowerW() + "W（主因:" + main + "）");
            }

            RequestDecision decision =
                    granted >= requested - 1e-9 ? RequestDecision.ACCEPTED : RequestDecision.CLIPPED;
            double reservedWh = granted * req.durationMs() / 3_600_000.0;
            // 预留只在账本冻结能量（不动物理剩余）；物理能量在结算时按实耗扣减
            Reservation r = ledger.reserve(req.requestId(), req.sourceId(),
                    requested, granted, req.durationMs(), reservedWh, expiresAt);

            List<RejectReason> reasons = decision == RequestDecision.ACCEPTED
                    ? List.of(RejectReason.NONE) : List.copyOf(binders);
            String msg = decision == RequestDecision.ACCEPTED
                    ? "全额受理"
                    : "按绑定约束裁剪至 " + fmt(granted) + "W，冻结 " + fmt(reservedWh) + "Wh";
            log.info("[请求] 结果 id={} {} granted={}W reserved={}Wh 可调度={}Wh 版本 {}->{}",
                    req.requestId(), decision, fmt(granted), fmt(reservedWh),
                    fmt(battery.remainingWh() - ledger.frozenWh() - faultManager.safetyReserveWh()),
                    version, ledger.version());
            return new PowerDecision(r.requestId(), decision, requested,
                    r.grantedPowerW(), effectiveBinding, ledger.version(), reasons, msg);
        }
    }

    /** 撤销：幂等，重复撤销不产生事件、不重复释放冻结。物理剩余不变（能量本未实扣）。 */
    public PowerLedger.ReleaseOutcome release(String requestId, String sourceId) {
        synchronized (lock) {
            reapExpiredInLock();
            Reservation existing = ledger.find(requestId).orElse(null);
            PowerLedger.ReleaseOutcome outcome = ledger.release(requestId, sourceId);
            if (outcome == PowerLedger.ReleaseOutcome.RELEASED) {
                log.info("[撤销] id={} 来源={} 结果={} 释放冻结={}Wh 可调度={}Wh",
                        requestId, sourceId, outcome, fmt(existing.reservedWh()),
                        fmt(battery.remainingWh() - ledger.frozenWh()
                                - faultManager.safetyReserveWh()));
            } else {
                log.info("[撤销] id={} 来源={} 结果={}（无能量变动）",
                        requestId, sourceId, outcome);
            }
            return outcome;
        }
    }

    /** 结算：物理剩余按实际耗能扣减；冻结同步解除。终止态重复结算安全。 */
    public PowerLedger.SettleResult settle(String requestId, String sourceId,
                                           double actualConsumedWh) {
        synchronized (lock) {
            reapExpiredInLock();
            double before = battery.remainingWh();
            PowerLedger.SettleResult result = ledger.settle(requestId, sourceId, actualConsumedWh);
            if (result.outcome() == PowerLedger.SettleOutcome.SETTLED) {
                battery.consumeEnergy(actualConsumedWh);
                log.info("[结算] id={} 来源={} 实耗={}Wh 物理剩余 {}->{}Wh 冻结={}Wh",
                        requestId, sourceId, fmt(actualConsumedWh),
                        fmt(before), fmt(battery.remainingWh()), fmt(ledger.frozenWh()));
            } else {
                log.info("[结算] id={} 来源={} 结果={}（无能量变动）",
                        requestId, sourceId, result.outcome());
            }
            return result;
        }
    }

    /**
     * 续期：对同一笔活动预留延长有效期 extendMs。
     * 只延长租约，不重复冻结能量、不改变已授予功率；
     * 已过期/已撤销/已结算的预留返回可区分结果码且不会被重新激活。
     */
    public PowerLedger.RenewResult renew(String requestId, String sourceId, long extendMs) {
        synchronized (lock) {
            reapExpiredInLock();
            long before = ledger.find(requestId).map(Reservation::expiresAtEpochMs)
                    .orElse(Reservation.NEVER_EXPIRES);
            PowerLedger.RenewResult result = ledger.renew(requestId, sourceId, extendMs);
            if (result.outcome() == PowerLedger.RenewOutcome.RENEWED) {
                log.info("[续期] id={} 来源={} 延长={}ms 到期 {} -> {} 冻结={}Wh（无重复冻结）",
                        requestId, sourceId, extendMs,
                        before == Reservation.NEVER_EXPIRES ? "NEVER" : before,
                        result.expiresAtEpochMs() == Reservation.NEVER_EXPIRES
                                ? "NEVER" : result.expiresAtEpochMs(),
                        fmt(ledger.frozenWh()));
            } else {
                log.info("[续期] id={} 来源={} 延长={}ms 结果={}（不重新激活，无能量变动）",
                        requestId, sourceId, extendMs, result.outcome());
            }
            return result;
        }
    }

    public SystemSnapshot snapshot() {
        synchronized (lock) {
            reapExpiredInLock();
            double physical = battery.remainingWh();
            double frozen = ledger.frozenWh();
            double reserve = faultManager.safetyReserveWh();
            return new SystemSnapshot(System.currentTimeMillis(),
                    faultManager.state(),
                    physical,
                    frozen,
                    physical - frozen - reserve,
                    reserve,
                    battery.voltageV(), battery.currentA(), battery.tempC(),
                    inverter.tempC(), motor.tempC(), motor.currentA(),
                    battery.sensorValid() && inverter.sensorValid() && motor.sensorValid(),
                    ledger.version(), eventStore.loadAll().size(),
                    faultManager.activeFaults(), ledger.activeReservations());
        }
    }

    public List<Reservation> activeReservations() {
        synchronized (lock) {
            reapExpiredInLock();
            return ledger.activeReservations();
        }
    }

    public long eventCount() {
        synchronized (lock) { return eventStore.loadAll().size(); }
    }

    /** 锁内回收已到期预留并打印流水（调用方必须持有 lock）。 */
    private void reapExpiredInLock() {
        long now = timeSource.nowMs();
        List<ReservationExpired> events = ledger.expireDue(now);
        for (ReservationExpired e : events) {
            log.info("[租约] 自动回收 id={} 来源={} 返还冻结={}Wh 到期={} 回收时刻={} 可调度={}Wh",
                    e.requestId(), e.sourceId(), fmt(e.refundedWh()),
                    e.expiresAtEpochMs(), now,
                    fmt(battery.remainingWh() - ledger.frozenWh()
                            - faultManager.safetyReserveWh()));
        }
    }

    private static long saturatedAdd(long a, long b) {
        long sum = a + b;
        return sum < a ? Long.MAX_VALUE : sum;
    }

    private PowerDecision reject(PowerRequest req, RejectReason reason,
                                 String message, double bindingLimitW, long version) {        log.info("[请求] 结果 id={} REJECTED 原因={} 说明={}",
                req.requestId(), reason, message);
        return new PowerDecision(req.requestId(), RequestDecision.REJECTED,
                req.requestedPowerW(), 0, bindingLimitW, version, List.of(reason), message);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
