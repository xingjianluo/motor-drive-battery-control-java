package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import com.github.xingjianluo.motordrivebatterycontroljava.power.model.BatteryModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultReport;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.InverterModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.LimitBound;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.MotorModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.OperationalState;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.RejectionReason;
import com.github.xingjianluo.motordrivebatterycontroljava.power.service.PowerCoordinator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障链路：重复上报去重、迟滞恢复、停机锁存、容量不重复扣减。
 */
class FaultStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(FaultStateMachineTest.class);
    private final AtomicLong clock = new AtomicLong(2_000);

    private PowerCoordinator coordinator() {
        BatteryModel battery = new BatteryModel(350.0, 60.0, 300.0, 0.9);
        InverterModel inverter = new InverterModel(80000.0);
        MotorModel motor = new MotorModel(12000, 35.0, 80000.0);
        return new PowerCoordinator(battery, inverter, motor, new InMemoryEventLog(), 1.0);
    }

    private FaultReport report(String id, String key, FaultType type, double value) {
        return new FaultReport(id, key, type, value, clock.incrementAndGet());
    }

    private PowerRequest power(String id, double watts) {
        return new PowerRequest(id, "test-entry", watts, clock.incrementAndGet());
    }

    @Test
    void overCurrentStopsAndRejectsNewRequests() {
        PowerCoordinator c = coordinator();
        log.info("用例1 输入：过流上报 320A（触发阈值 >=300A）");
        FaultDecision d = c.reportFault(report("p1", "oc-1", FaultType.OVER_CURRENT, 320.0));

        assertFalse(d.duplicate());
        assertEquals(OperationalState.RUNNING, d.previousState());
        assertEquals(OperationalState.STOPPED, d.resultingState());
        PowerDecision rejected = c.request(power("r1", 10000));
        assertFalse(rejected.accepted());
        assertEquals(RejectionReason.SYSTEM_STOPPED, rejected.rejection());
        log.info("用例1 判定依据：{}；停机后请求：{}；流水：{}", d.explanation(), rejected.explanation(), c.snapshot());
    }

    @Test
    void duplicateFaultReportsDoNotToggleStateOrAddEvents() {
        InMemoryEventLog events = new InMemoryEventLog();
        BatteryModel battery = new BatteryModel(350.0, 60.0, 300.0, 0.9);
        InverterModel inverter = new InverterModel(80000.0);
        PowerCoordinator c = new PowerCoordinator(battery, inverter,
                new MotorModel(12000, 35.0, 80000.0), events, 1.0);

        // 先有一笔预留，验证故障重复上报不会改变账本（不重复扣减可用容量）
        c.request(power("hold", 50000));
        double reservedBefore = c.snapshot().ledger().reservedWatts();
        double remainingBefore = c.snapshot().ledger().remainingWatts();

        FaultDecision first = c.reportFault(report("p1", "oc-1", FaultType.OVER_CURRENT, 320.0));
        int eventsAfterFirst = events.size();
        FaultDecision second = c.reportFault(report("p2", "oc-1", FaultType.OVER_CURRENT, 330.0));
        FaultDecision third = c.reportFault(report("p3", "oc-1", FaultType.OVER_CURRENT, 340.0));

        assertFalse(first.duplicate());
        assertTrue(second.duplicate(), "同一 faultKey 第二次上报必须判为重复");
        assertTrue(third.duplicate());
        assertEquals(OperationalState.STOPPED, second.resultingState());
        assertEquals(OperationalState.STOPPED, third.resultingState(), "状态不得反复切换");
        assertEquals(eventsAfterFirst, events.size(), "重复上报不得追加事件");
        assertEquals(reservedBefore, c.snapshot().ledger().reservedWatts(), 1e-6,
                "重复故障上报不得改变已预留容量");
        assertEquals(remainingBefore, c.snapshot().ledger().remainingWatts(), 1e-6,
                "重复故障上报不得重复扣减可用容量");
        log.info("用例2 输入：同一 faultKey=oc-1 连续三次上报 320/330/340A");
        log.info("用例2 判定：first.duplicate={} second.duplicate={} third.duplicate={}",
                first.duplicate(), second.duplicate(), third.duplicate());
        log.info("用例2 流水：事件数停留在 {}，账本={}", eventsAfterFirst, c.snapshot().ledger());
    }

    @Test
    void overTemperatureDeratesAndRecoveryRequiresHysteresis() {
        PowerCoordinator c = coordinator();

        FaultDecision raised = c.reportFault(report("t1", "ot-1", FaultType.OVER_TEMPERATURE, 95.0));
        assertEquals(OperationalState.DERATED, raised.resultingState(), "过温 -> 降级");
        PowerDecision d = c.request(power("r1", 80000));
        assertTrue(d.accepted() && d.clipped());
        assertEquals(40000.0, d.grantedWatts(), 1e-6, "降级系数 0.5：80kW -> 40kW");
        assertEquals(LimitBound.DERATE_FACTOR, d.bindingLimit());
        log.info("用例3 降级后请求 80000W -> 授予 {}W，依据：{}", d.grantedWatts(), d.explanation());

        assertFalse(c.clearFault("ot-1", 85.0), "85℃ 未到恢复阈值 80℃，不得自行复位");
        assertEquals(OperationalState.DERATED, c.snapshot().state(), "恢复条件未满足必须锁存");
        assertTrue(c.clearFault("ot-1", 75.0), "75℃ 满足恢复条件");
        assertEquals(OperationalState.RUNNING, c.snapshot().state());
        log.info("用例3 输入：85℃ 恢复上报（期望失败），再 75℃（期望成功）；流水：{}", c.snapshot().state());
    }

    @Test
    void clearingOneFaultKeepsOtherFaultsEffective() {
        PowerCoordinator c = coordinator();
        c.reportFault(report("t1", "ot-1", FaultType.OVER_TEMPERATURE, 95.0));
        c.reportFault(report("u1", "uv-1", FaultType.UNDER_VOLTAGE, 290.0));
        assertEquals(OperationalState.STOPPED, c.snapshot().state(), "欠压升级为停机");

        assertTrue(c.clearFault("ot-1", 70.0));
        assertEquals(OperationalState.STOPPED, c.snapshot().state(),
                "只清除过温而欠压仍在，不得复位到运行态");
        assertFalse(c.clearFault("uv-1", 320.0), "320V 未到欠压恢复阈值 330V");
        assertTrue(c.clearFault("uv-1", 335.0));
        assertEquals(OperationalState.RUNNING, c.snapshot().state());
        log.info("用例4 输入：过温+欠压双故障，先清过温、再以 320V/335V 清欠压；流水：{}", c.snapshot());
    }

    @Test
    void sensorInvalidDeratesUntilValidReadingReported() {
        PowerCoordinator c = coordinator();
        FaultDecision d = c.reportFault(report("s1", "si-1", FaultType.SENSOR_INVALID, Double.NaN));
        assertEquals(OperationalState.DERATED, d.resultingState());
        PowerDecision clipped = c.request(power("r1", 80000));
        assertEquals(20000.0, clipped.grantedWatts(), 1e-6, "传感器无效降级系数 0.25：80kW -> 20kW");

        assertFalse(c.clearFault("si-1", Double.NaN), "NaN 不是有效读数，不能恢复");
        assertTrue(c.clearFault("si-1", 350.0), "上报有效有限读数后恢复");
        assertEquals(OperationalState.RUNNING, c.snapshot().state());
        log.info("用例5 输入：NaN 触发传感器无效；授予 {}W；NaN 恢复被拒；350V 恢复成功；流水：{}",
                clipped.grantedWatts(), c.snapshot());
    }

    @Test
    void belowTripValueReportDoesNotRaiseFault() {
        PowerCoordinator c = coordinator();
        FaultDecision d = c.reportFault(report("p1", "oc-x", FaultType.OVER_CURRENT, 250.0));
        assertFalse(d.duplicate());
        assertEquals(OperationalState.RUNNING, d.resultingState(), "250A 未达 300A 阈值，不应降级/停机");
        assertFalse(c.snapshot().activeFaults().containsKey("oc-x"));
        log.info("用例6 输入：250A（<300A 阈值）；判定依据：{}", d.explanation());
    }
}
