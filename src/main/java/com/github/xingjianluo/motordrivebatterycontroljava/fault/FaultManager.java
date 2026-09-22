package com.github.xingjianluo.motordrivebatterycontroljava.fault;

import java.util.EnumMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.motordrivebatterycontroljava.device.Battery;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Inverter;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Motor;
import com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.FaultEvent;
import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;

/**
 * 故障状态机（仅在协调器锁内访问）：
 * <pre>
 *                 过温(唯一活动故障)
 *   RUNNING ───────────────────────▶ DERATED
 *      ▲  ◀─────────────────────────── │
 *      │ 过温消除且无严重故障            │
 *      │  过流/欠压/传感器无效            │
 *      └──────────────┐         ┌───────┘
 *                     ▼         ▼
 *                    SHUTDOWN（闭锁，恢复条件满足且显式清除后才离开）
 * </pre>
 * 规则：
 * 1) 同一故障重复触发上报：已闭锁则不迁移、不重复冻结安全储备；
 * 2) 进入 SHUTDOWN 时冻结一次 shutdownReserveWh 安全储备，离开时返还（仅一次）；
 * 3) 恢复必须显式 clearFault 且读数满足迟滞条件，否则保持闭锁。
 */
public class FaultManager {

    private static final Logger log = LoggerFactory.getLogger(FaultManager.class);

    /** 一次状态迁移的可解释结果。 */
    public record Transition(boolean eventRecorded,
                             boolean stateChanged,
                             FaultState oldState,
                             FaultState newState,
                             boolean newlyLatched,
                             double reserveDeltaWh,
                             String reason) { }

    private static final double UV_RECOVER_MARGIN_V = 10.0;
    private static final double OC_RECOVER_FACTOR = 0.9;
    private static final double MOTOR_TEMP_RECOVER_MARGIN_C = 10.0;

    private final EventStore eventStore;
    private final Battery battery;
    private final Inverter inverter;
    private final Motor motor;
    private final double overTempDerateFactor;
    private final double shutdownReserveWh;

    private final Map<FaultType, Boolean> active = new EnumMap<>(FaultType.class);
    private FaultState state = FaultState.RUNNING;
    private double safetyReserveWh = 0;
    private long nextSequence = 1;
    private Transition lastTransition;

    public FaultManager(EventStore eventStore, Battery battery, Inverter inverter,
                        Motor motor, double overTempDerateFactor,
                        double shutdownReserveWh) {
        this.eventStore = eventStore;
        this.battery = battery;
        this.inverter = inverter;
        this.motor = motor;
        this.overTempDerateFactor = overTempDerateFactor;
        this.shutdownReserveWh = shutdownReserveWh;
    }

    public FaultState state() { return state; }

    public boolean isFaultActive(FaultType type) {
        return active.getOrDefault(type, false);
    }

    public Map<FaultType, Boolean> activeFaults() {
        return Map.copyOf(active);
    }

    public double safetyReserveWh() { return safetyReserveWh; }

    public double powerFactor() {
        return switch (state) {
            case RUNNING -> 1.0;
            case DERATED -> overTempDerateFactor;
            case SHUTDOWN -> 0.0;
        };
    }

    /**
     * 显式故障上报：先持久化事件（输入留痕，重复上报也记录），再幂等投影。
     * 协调器已先把 reading 应用到设备模型。
     */
    public Transition report(FaultType type, boolean activeFlag, SensorReading reading) {
        long seq = Math.max(nextSequence, eventStore.lastSequence() + 1);
        nextSequence = seq + 1;
        FaultEvent event = new FaultEvent(seq, System.currentTimeMillis(),
                type, activeFlag, reading);
        eventStore.append(event);
        return dispatch(type, activeFlag, reading);
    }

    /** 重放路径：只投影，不写日志。 */
    public void applyEvent(FaultEvent e) {
        Transition t = dispatch(e.type(), e.active(), e.reading());
        bumpSequence(e.sequence());
        lastTransition = t;
    }

    /** 重放路径：投影故障事件并返回迁移结果（供协调器联动安全储备能量）。 */
    public Transition applyEventReplay(FaultEvent e) {
        applyEvent(e);
        return lastTransition;
    }

    private Transition dispatch(FaultType type, boolean activeFlag, SensorReading reading) {
        return activeFlag ? latch(type, reading) : clear(type, reading);
    }

    private Transition latch(FaultType type, SensorReading reading) {
        FaultState old = state;
        if (active.getOrDefault(type, false)) {
            // 重复上报：输入已记录，但不重复迁移、不重复扣减
            log.info("[故障] 重复上报 type={} 已闭锁 -> 状态保持 {}，无扣减", type, state);
            return new Transition(true, false, old, state, false, 0,
                    "故障 " + type + " 已处于闭锁态，重复上报被幂等忽略");
        }
        active.put(type, true);
        FaultState next = aggregate();
        double delta = 0;
        if (old != FaultState.SHUTDOWN && next == FaultState.SHUTDOWN) {
            safetyReserveWh = shutdownReserveWh;
            delta = shutdownReserveWh;
        }
        state = next;
        log.info("[故障] 首次闭锁 type={} 依据={} -> 状态迁移 {} -> {}，冻结安全储备={}Wh",
                type, basisText(type, reading), old, next, delta);
        return new Transition(true, next != old, old, next, true, delta,
                "故障 " + type + " 首次闭锁，迁移 " + old + " -> " + next);
    }

    private Transition clear(FaultType type, SensorReading reading) {
        FaultState old = state;
        if (!active.getOrDefault(type, false)) {
            log.info("[故障] 清除上报 type={} 但本就未闭锁 -> 状态保持 {}", type, state);
            return new Transition(true, false, old, state, false, 0,
                    "故障 " + type + " 未闭锁，无需清除");
        }
        if (!recoverySatisfied(type, reading)) {
            log.info("[故障] 清除被拒绝 type={} 恢复条件未满足（本次上报依据={}）-> 保持 {}",
                    type, basisText(type, reading), state);
            return new Transition(true, false, old, state, false, 0,
                    "故障 " + type + " 恢复条件未满足（迟滞阈值），保持 " + state);
        }
        active.put(type, false);
        FaultState next = aggregate();
        double delta = 0;
        if (old == FaultState.SHUTDOWN && next != FaultState.SHUTDOWN) {
            delta = -safetyReserveWh;
            safetyReserveWh = 0;
        }
        state = next;
        log.info("[故障] 满足恢复并清除 type={} -> 状态迁移 {} -> {}，返还安全储备={}Wh",
                type, old, next, -delta);
        return new Transition(true, next != old, old, next, false, delta,
                "故障 " + type + " 恢复条件满足，迁移 " + old + " -> " + next);
    }

    /** 依据当前闭锁故障集合聚合系统状态。 */
    private FaultState aggregate() {
        if (isFaultActive(FaultType.OVER_CURRENT)
                || isFaultActive(FaultType.UNDER_VOLTAGE)
                || isFaultActive(FaultType.SENSOR_INVALID)) {
            return FaultState.SHUTDOWN;
        }
        if (isFaultActive(FaultType.OVER_TEMPERATURE)) {
            return FaultState.DERATED;
        }
        return FaultState.RUNNING;
    }

    /**
     * 迟滞恢复条件判定。优先使用本次清除上报携带的读数；
     * 读数为空（人工清除且无附读数）时回退到设备最新遥测。
     */
    private boolean recoverySatisfied(FaultType type, SensorReading r) {
        if (r == null) {
            return switch (type) {
                case OVER_CURRENT ->
                        motor.currentA() <= motor.spec().ratedCurrentA() * OC_RECOVER_FACTOR;
                case UNDER_VOLTAGE ->
                        battery.voltageV() >= battery.spec().undervoltageV() + UV_RECOVER_MARGIN_V;
                case OVER_TEMPERATURE ->
                        battery.tempC() <= battery.spec().recoverTempC()
                                && inverter.tempC() <= inverter.spec().recoverTempC()
                                && motor.tempC() <= motor.spec().overTempC() - MOTOR_TEMP_RECOVER_MARGIN_C;
                case SENSOR_INVALID ->
                        battery.sensorValid() && inverter.sensorValid() && motor.sensorValid();
            };
        }
        if (!r.valid()) {
            return false;
        }
        return switch (type) {
            case OVER_CURRENT -> r.motorCurrentA() <= motor.spec().ratedCurrentA() * OC_RECOVER_FACTOR;
            case UNDER_VOLTAGE -> r.batteryVoltageV() >= battery.spec().undervoltageV() + UV_RECOVER_MARGIN_V;
            case OVER_TEMPERATURE -> r.batteryTempC() <= battery.spec().recoverTempC()
                    && r.inverterTempC() <= inverter.spec().recoverTempC()
                    && r.motorTempC() <= motor.spec().overTempC() - MOTOR_TEMP_RECOVER_MARGIN_C;
            case SENSOR_INVALID -> r.valid();
        };
    }

    private String basisText(FaultType type, SensorReading reading) {
        if (reading == null) {
            return "无读数(人工上报)";
        }
        if (!reading.valid()) {
            return "读数无效";
        }
        return switch (type) {
            case OVER_CURRENT -> "电机电流=" + reading.motorCurrentA() + "A 阈值="
                    + motor.spec().ratedCurrentA() + "A";
            case UNDER_VOLTAGE -> "电池电压=" + reading.batteryVoltageV() + "V 阈值="
                    + battery.spec().undervoltageV() + "V";
            case OVER_TEMPERATURE -> "温度 电池=" + reading.batteryTempC()
                    + "℃ 逆变器=" + reading.inverterTempC()
                    + "℃ 电机=" + reading.motorTempC() + "℃";
            case SENSOR_INVALID -> "传感器有效标志=" + reading.valid();
        };
    }

    private void bumpSequence(long seq) {
        nextSequence = Math.max(nextSequence, seq + 1);
    }
}
