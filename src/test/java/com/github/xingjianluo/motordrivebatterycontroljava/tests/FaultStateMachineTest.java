package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultManager;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultState;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RequestDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.support.TestSystems;

/**
 * 故障链路测试：过流/欠压/传感器无效 -> 停机；过温 -> 降级；
 * 重复上报不重复迁移/扣减；恢复条件未满足不得自行复位。
 */
@ExtendWith(com.github.xingjianluo.motordrivebatterycontroljava.support.TestCaseResultLogger.class)
class FaultStateMachineTest {

    private static final Logger log = LoggerFactory.getLogger(FaultStateMachineTest.class);

    private PowerCoordinationService ready() {
        PowerCoordinationService svc = TestSystems.newService();
        svc.ingestReading(TestSystems.healthy());
        return svc;
    }

    private SensorReading reading(double voltageV, double motorCurrentA,
                                  double batTemp, double invTemp, double motTemp) {
        return new SensorReading(System.currentTimeMillis(),
                voltageV, motorCurrentA, batTemp, invTemp, motTemp, motorCurrentA, true);
    }

    @Test
    void overCurrentShutsDownAndRepeatedReportDoesNotDeductTwice() {
        PowerCoordinationService svc = ready();
        SensorReading oc = reading(360, 300, 30, 35, 40); // 额定 250A -> 过流
        log.info(">>> 用例: 过流停机 + 重复上报。输入 I={}A 阈值=250A", oc.motorCurrentA());

        var t1 = svc.reportFault(FaultType.OVER_CURRENT, oc);
        double reserveAfterFirst = svc.snapshot().safetyReserveWh();
        double remainingAfterFirst = svc.snapshot().remainingWh();
        var t2 = svc.reportFault(FaultType.OVER_CURRENT, oc);
        var t3 = svc.reportFault(FaultType.OVER_CURRENT, oc);

        assertEquals(FaultState.SHUTDOWN, svc.faultState());
        assertTrue(t1.stateChanged());
        assertEquals(TestSystems.SHUTDOWN_RESERVE_WH, reserveAfterFirst, 1e-9);
        // 重复上报：不再迁移、不再扣减
        assertFalse(t2.stateChanged());
        assertFalse(t2.newlyLatched());
        assertEquals(0, t2.reserveDeltaWh(), 1e-9);
        assertFalse(t3.stateChanged());
        assertEquals(reserveAfterFirst, svc.snapshot().safetyReserveWh(), 1e-9);
        assertEquals(remainingAfterFirst, svc.snapshot().remainingWh(), 1e-9);
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().remainingWh(), 1e-9);
        log.info("<<< 断言通过: 三次上报后状态={} 安全储备={}Wh 物理剩余={}Wh（仅扣一次）",
                svc.faultState(), svc.snapshot().safetyReserveWh(),
                svc.snapshot().remainingWh());
    }

    @Test
    void shutdownRejectsPowerAndAutoReleasesActiveReservationsOnce() {
        PowerCoordinationService svc = ready();
        svc.request(new PowerRequest("p1", "s1", 20_000, 3_600_000));
        assertEquals(1, svc.activeReservations().size());
        log.info(">>> 用例: 已有活动预留 1 笔（冻结 20000Wh），随后过流停机");

        svc.reportFault(FaultType.OVER_CURRENT, reading(360, 400, 30, 35, 40));

        assertEquals(0, svc.activeReservations().size());
        // 预留冻结随停机释放，安全储备仍冻结；物理剩余不变（未结算实耗）
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().remainingWh(), 1e-6);
        assertEquals(TestSystems.SHUTDOWN_RESERVE_WH, svc.snapshot().safetyReserveWh(), 1e-6);
        assertEquals(TestSystems.CAPACITY_WH - TestSystems.SHUTDOWN_RESERVE_WH,
                svc.snapshot().availableWh(), 1e-6);

        PowerDecision d = svc.request(new PowerRequest("p2", "s1", 1_000, 100));
        assertEquals(RequestDecision.REJECTED, d.decision());
        assertEquals("SYSTEM_SHUTDOWN", d.reasons().get(0).name());
        log.info("<<< 断言通过: 活动预留清零（冻结已返还），停机后请求被拒原因={}", d.reasons());
    }

    @Test
    void overTemperatureDeratesAndClearRequiresHysteresis() {
        PowerCoordinationService svc = ready();
        SensorReading hot = reading(360, 20, 60, 35, 40); // 电池 >55℃ 过温
        log.info(">>> 用例: 过温降级 Tb=60℃ 阈值55/恢复45");
        svc.reportFault(FaultType.OVER_TEMPERATURE, hot);
        assertEquals(FaultState.DERATED, svc.faultState());

        // 降级态请求 80kW：系数 0.5，绑定约束 *0.5。
        // 电流折算 360*300=108kW、逆变器 80kW -> binding=80kW，*0.5=40kW
        PowerDecision d = svc.request(new PowerRequest("d1", "s1", 80_000, 100));
        assertEquals(RequestDecision.CLIPPED, d.decision());
        assertEquals(40_000, d.grantedPowerW(), 1e-6);
        log.info("降级态授予={}W（系数0.5）", d.grantedPowerW());

        // 温度仍高（50℃，介于 45~55 之间）：恢复条件未满足，清除被拒，状态保持 DERATED
        SensorReading warm = reading(360, 20, 50, 35, 40);
        var denied = svc.clearFault(FaultType.OVER_TEMPERATURE, warm);
        assertFalse(denied.stateChanged());
        assertEquals(FaultState.DERATED, svc.faultState());
        log.info("未达迟滞恢复阈值时清除被拒，状态保持={}", svc.faultState());

        // 冷却到恢复阈值以下：允许清除，回到 RUNNING（不会自行复位，必须显式清除）
        SensorReading cool = reading(360, 20, 40, 35, 40);
        var restored = svc.clearFault(FaultType.OVER_TEMPERATURE, cool);
        assertTrue(restored.stateChanged());
        assertEquals(FaultState.RUNNING, svc.faultState());
        log.info("<<< 断言通过: 冷却后显式清除，状态恢复={}", svc.faultState());
    }

    @Test
    void underVoltageShutsDownAndRecoveryNeedsMargin() {
        PowerCoordinationService svc = ready();
        svc.reportFault(FaultType.UNDER_VOLTAGE, reading(280, 20, 30, 35, 40));
        assertEquals(FaultState.SHUTDOWN, svc.faultState());
        log.info(">>> 用例: 欠压停机 U=280V；恢复需 >= 310V（300+10迟滞）");

        // 305V 已高于故障阈值 300 但不足恢复裕量：不得复位
        var notYet = svc.clearFault(FaultType.UNDER_VOLTAGE, reading(305, 20, 30, 35, 40));
        assertFalse(notYet.stateChanged());
        assertEquals(FaultState.SHUTDOWN, svc.faultState());

        var ok = svc.clearFault(FaultType.UNDER_VOLTAGE, reading(315, 20, 30, 35, 40));
        assertTrue(ok.stateChanged());
        assertEquals(FaultState.RUNNING, svc.faultState());
        log.info("<<< 断言通过: 305V 不复位，315V 显式清除后恢复 RUNNING");
    }

    @Test
    void invalidSensorReadingShutsDownAutomatically() {
        PowerCoordinationService svc = ready();
        log.info(">>> 用例: 传感器读数无效帧自动触发停机");
        svc.ingestReading(SensorReading.invalid(System.currentTimeMillis()));

        assertEquals(FaultState.SHUTDOWN, svc.faultState());
        PowerDecision d = svc.request(new PowerRequest("x", "s", 5_000, 100));
        assertEquals(RequestDecision.REJECTED, d.decision());

        // 健康读数恢复 + 显式清除
        svc.ingestReading(TestSystems.healthy());
        var t = svc.clearFault(FaultType.SENSOR_INVALID, TestSystems.healthy());
        assertTrue(t.stateChanged());
        assertEquals(FaultState.RUNNING, svc.faultState());
        log.info("<<< 断言通过: 无效读数停机拒绝原因={}，恢复后={}", d.reasons(), svc.faultState());
    }

    @Test
    void noSelfResetBeforeClearEvenWhenReadingsRecover() {
        PowerCoordinationService svc = ready();
        svc.reportFault(FaultType.OVER_CURRENT, reading(360, 400, 30, 35, 40));
        // 读数自行恢复健康，但未显式清除：仍闭锁
        svc.ingestReading(TestSystems.healthy());
        svc.ingestReading(TestSystems.healthy());
        log.info(">>> 用例: 读数已恢复但未显式清除");
        assertEquals(FaultState.SHUTDOWN, svc.faultState());
        log.info("<<< 断言通过: 状态保持 {}，无自行复位", svc.faultState());
    }
}
