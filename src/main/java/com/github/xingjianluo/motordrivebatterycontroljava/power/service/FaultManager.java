package com.github.xingjianluo.motordrivebatterycontroljava.power.service;

import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultReport;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.OperationalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 故障状态机。
 * <p>故障到处置状态的固定映射（组合状态取最严，降级系数取最小值）：
 * <ul>
 *   <li>{@link FaultType#OVER_CURRENT}  过流    -> STOPPED</li>
 *   <li>{@link FaultType#UNDER_VOLTAGE} 欠压    -> STOPPED</li>
 *   <li>{@link FaultType#OVER_TEMPERATURE} 过温 -> DERATED（系数 0.5）</li>
 *   <li>{@link FaultType#SENSOR_INVALID} 传感器无效 -> DERATED（系数 0.25）</li>
 * </ul>
 * <p>去重：同一 faultKey 的重复上报标记 duplicate=true，不切换状态、不记录事件、不重复扣减容量。
 * <p>恢复：必须满足迟滞恢复条件并显式上报清除；条件不满足返回 false；只要还有活动故障，
 * 系统状态就不会自行复位到 RUNNING。
 */
public class FaultManager {

    private static final Logger log = LoggerFactory.getLogger(FaultManager.class);

    private record ActiveFault(FaultType type, double measuredValue, long atNanos) {
    }

    /** 触发阈值 / 恢复阈值 / 处置状态 / 降级系数 */
    private record Policy(double trip, double recover, OperationalState severity,
                          double derateFactor, String tripRule, String recoverRule) {
    }

    private final Map<FaultType, Policy> policies = Map.of(
            FaultType.OVER_CURRENT,
            new Policy(300.0, 270.0, OperationalState.STOPPED, 0.0,
                    "电流 >= 300A", "电流 <= 270A"),
            FaultType.UNDER_VOLTAGE,
            new Policy(300.0, 330.0, OperationalState.STOPPED, 0.0,
                    "电压 <= 300V", "电压 >= 330V"),
            FaultType.OVER_TEMPERATURE,
            new Policy(90.0, 80.0, OperationalState.DERATED, 0.5,
                    "温度 >= 90℃", "温度 <= 80℃"),
            FaultType.SENSOR_INVALID,
            new Policy(Double.NaN, Double.NaN, OperationalState.DERATED, 0.25,
                    "读数非有限值（NaN/Infinity）", "上报有效有限读数"));

    private final Map<String, ActiveFault> active = new LinkedHashMap<>();

    /** 上报故障，返回处置结果（重复上报 duplicate=true 且状态不变） */
    public FaultDecision reportFault(FaultReport report) {
        OperationalState before = currentState();
        ActiveFault existing = active.get(report.faultKey());
        if (existing != null) {
            log.info("[故障] 重复上报 faultKey={} type={} value={} -> 判定为 duplicate，活动故障不变化、状态保持 {}",
                    report.faultKey(), report.type(), fmt(report.measuredValue()), before);
            return new FaultDecision(report.faultKey(), true, before, before,
                    "重复上报同一 faultKey，忽略（不重复切换状态、不重复扣减容量）");
        }
        if (!meetsTrip(report.type(), report.measuredValue())) {
            log.info("[故障] 上报但未达触发条件 faultKey={} type={} value={}（条件：{}），不建立故障",
                    report.faultKey(), report.type(), fmt(report.measuredValue()),
                    policies.get(report.type()).tripRule());
            return new FaultDecision(report.faultKey(), false, before, before,
                    "测量值未达触发条件：" + policies.get(report.type()).tripRule());
        }
        active.put(report.faultKey(),
                new ActiveFault(report.type(), report.measuredValue(), report.reportedAtNanos()));
        OperationalState after = currentState();
        log.info("[故障] 故障触发 faultKey={} type={} value={} 依据[{}] -> 状态迁移 {} => {}",
                report.faultKey(), report.type(), fmt(report.measuredValue()),
                policies.get(report.type()).tripRule(), before, after);
        return new FaultDecision(report.faultKey(), false, before, after,
                "满足触发条件：" + policies.get(report.type()).tripRule());
    }

    /** 清除处置结果（未成功时 cleared=false，type 可能为 null） */
    public record ClearResult(boolean cleared,
                              FaultType type,
                              OperationalState before,
                              OperationalState after,
                              String explanation) {
    }

    /** 上报故障恢复（须满足恢复条件） */
    public ClearResult clearFault(String faultKey, double measuredValue) {
        OperationalState before = currentState();
        ActiveFault f = active.get(faultKey);
        if (f == null) {
            log.info("[故障] 恢复上报未命中活动故障 faultKey={}，忽略", faultKey);
            return new ClearResult(false, null, before, before, "无此活动故障");
        }
        if (!meetsRecovery(f.type(), measuredValue)) {
            log.info("[故障] 恢复条件未满足 faultKey={} type={} value={}（需要：{}），状态保持 {}",
                    faultKey, f.type(), fmt(measuredValue), policies.get(f.type()).recoverRule(), before);
            return new ClearResult(false, f.type(), before, before,
                    "恢复条件未满足，需要：" + policies.get(f.type()).recoverRule());
        }
        active.remove(faultKey);
        OperationalState after = currentState();
        log.info("[故障] 故障清除 faultKey={} type={} value={} 依据[{}] -> 状态迁移 {} => {}",
                faultKey, f.type(), fmt(measuredValue), policies.get(f.type()).recoverRule(), before, after);
        return new ClearResult(true, f.type(), before, after,
                "满足恢复条件：" + policies.get(f.type()).recoverRule());
    }

    private boolean meetsTrip(FaultType type, double v) {
        return switch (type) {
            case OVER_CURRENT -> v >= policies.get(type).trip();
            case OVER_TEMPERATURE -> v >= policies.get(type).trip();
            case UNDER_VOLTAGE -> v <= policies.get(type).trip();
            case SENSOR_INVALID -> !Double.isFinite(v);
        };
    }

    private boolean meetsRecovery(FaultType type, double v) {
        return switch (type) {
            case OVER_CURRENT -> Double.isFinite(v) && v <= policies.get(type).recover();
            case OVER_TEMPERATURE -> Double.isFinite(v) && v <= policies.get(type).recover();
            case UNDER_VOLTAGE -> Double.isFinite(v) && v >= policies.get(type).recover();
            case SENSOR_INVALID -> Double.isFinite(v);
        };
    }

    public OperationalState currentState() {
        OperationalState state = OperationalState.RUNNING;
        for (ActiveFault f : active.values()) {
            OperationalState s = policies.get(f.type()).severity();
            if (s == OperationalState.STOPPED) {
                return OperationalState.STOPPED;
            }
            if (s == OperationalState.DERATED) {
                state = OperationalState.DERATED;
            }
        }
        return state;
    }

    /** 当前降级系数（活动故障系数取最小值；无故障为 1.0） */
    public double derateFactor() {
        if (currentState() == OperationalState.STOPPED) {
            return 0.0;
        }
        double factor = 1.0;
        for (ActiveFault f : active.values()) {
            factor = Math.min(factor, policies.get(f.type()).derateFactor());
        }
        return factor;
    }

    public Map<String, FaultType> activeFaults() {
        Map<String, FaultType> view = new LinkedHashMap<>();
        for (Map.Entry<String, ActiveFault> e : active.entrySet()) {
            view.put(e.getKey(), e.getValue().type());
        }
        return Map.copyOf(view);
    }

    /** 事件重放：按已记录的 FaultRaised 恢复活动故障（不产生新日志） */
    void replayFaultRaised(String faultKey, FaultType type, double measuredValue, long atNanos) {
        active.put(faultKey, new ActiveFault(type, measuredValue, atNanos));
    }

    /** 事件重放：按已记录的 FaultCleared 移除故障 */
    void replayFaultCleared(String faultKey) {
        active.remove(faultKey);
    }

    private static String fmt(double v) {
        return Double.isFinite(v) ? String.format("%.2f", v) : String.valueOf(v);
    }
}
