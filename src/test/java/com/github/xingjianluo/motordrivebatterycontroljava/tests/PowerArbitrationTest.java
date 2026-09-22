package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RejectReason;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RequestDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.service.SystemSnapshot;
import com.github.xingjianluo.motordrivebatterycontroljava.support.TestSystems;

/**
 * 功率仲裁测试：超额请求（逆变器硬上限/容量/电流）、裁剪一致性、重复请求幂等。
 * 日志打印输入、判定依据（见协调器流水）与状态迁移。
 */
class PowerArbitrationTest {

    private static final Logger log = LoggerFactory.getLogger(PowerArbitrationTest.class);

    private PowerCoordinationService readySystem() {
        PowerCoordinationService svc = TestSystems.newService();
        svc.ingestReading(TestSystems.healthy());
        return svc;
    }

    @Test
    void requestWithinLimitsIsAcceptedAndFreezesConsistentEnergy() {
        PowerCoordinationService svc = readySystem();
        long durationMs = 3_600_000; // 1 小时
        PowerRequest req = new PowerRequest("r1", "throttle", 30_000, durationMs);
        log.info(">>> 用例: 全额受理 请求={}W 时长=1h", req.requestedPowerW());

        PowerDecision d = svc.request(req);

        assertEquals(RequestDecision.ACCEPTED, d.decision());
        assertEquals(30_000, d.grantedPowerW(), 1e-6);
        assertEquals(RejectReason.NONE, d.reasons().get(0));

        SystemSnapshot s = svc.snapshot();
        // 冻结能量 = 30kW * 1h = 30000Wh；可调度能量与冻结严格一致
        assertEquals(30_000, s.frozenWh(), 1e-6);
        assertEquals(10_000, s.availableWh(), 1e-6);
        // 物理剩余只在结算时减少：预留阶段仍为满电量，能量守恒 remaining=available+frozen
        assertEquals(TestSystems.CAPACITY_WH, s.remainingWh(), 1e-6);
        log.info("<<< 断言通过: frozen={}Wh available={}Wh remaining={}Wh",
                s.frozenWh(), s.availableWh(), s.remainingWh());
    }

    @Test
    void requestAboveInverterHardLimitIsRejectedWithDistinctReason() {
        PowerCoordinationService svc = readySystem();
        PowerRequest req = new PowerRequest("r-hard", "throttle", 90_000, 1_000);
        log.info(">>> 用例: 超过逆变器硬上限 请求={}W 硬上限={}W",
                req.requestedPowerW(), TestSystems.INVERTER_LIMIT_W);

        PowerDecision d = svc.request(req);

        assertEquals(RequestDecision.REJECTED, d.decision());
        assertEquals(0, d.grantedPowerW(), 1e-9);
        assertEquals(RejectReason.INVERTER_HARDWARE_LIMIT, d.reasons().get(0));
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9);
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().remainingWh(), 1e-9);
        log.info("<<< 断言通过: 拒绝原因={} 能量零变动", d.reasons());
    }

    @Test
    void requestBeyondCapacityForDurationIsClippedNotExceeded() {
        PowerCoordinationService svc = readySystem();
        // 1 小时内要 50kW -> 需 50000Wh，容量 40000Wh 充足；
        // 但电流折算上限 = 350V*300A = 105kW，逆变器 80kW，故 50kW 全额受理。
        // 改为请求 80kW/1h：80000Wh > 40000Wh，容量折算=40000W -> 裁剪到 40kW
        PowerRequest req = new PowerRequest("r-clip-cap", "cruise", 80_000, 3_600_000);
        log.info(">>> 用例: 容量不足裁剪 请求={}W/1h 容量={}Wh",
                req.requestedPowerW(), TestSystems.CAPACITY_WH);

        PowerDecision d = svc.request(req);

        assertEquals(RequestDecision.CLIPPED, d.decision());
        assertEquals(40_000, d.grantedPowerW(), 1e-6);
        assertTrue(d.reasons().contains(RejectReason.INSUFFICIENT_REMAINING_CAPACITY));
        // 裁剪结果与冻结剩余容量一致：授予 40kW * 1h 正好冻结 40000Wh
        assertEquals(40_000, svc.snapshot().frozenWh(), 1e-6);
        assertEquals(0, svc.snapshot().availableWh(), 1e-6);
        log.info("<<< 断言通过: granted={}W 原因={} 冻结={}Wh",
                d.grantedPowerW(), d.reasons(), svc.snapshot().frozenWh());
    }

    @Test
    void currentLimitClipsAndIsDistinguishableFromCapacity() {
        // 用低欠压阈值的定制电池，使 200V 低压帧合法；此时电流折算 200V*300A=60kW < 逆变器 80kW
        PowerCoordinationService svc = new PowerCoordinationService(
                new com.github.xingjianluo.motordrivebatterycontroljava.device.Battery(
                        new com.github.xingjianluo.motordrivebatterycontroljava.device.BatterySpec(
                                TestSystems.CAPACITY_WH, 350, TestSystems.MAX_CURRENT_A,
                                150, 55, 45)),
                TestSystems.inverter(), TestSystems.motor(),
                new com.github.xingjianluo.motordrivebatterycontroljava.event.InMemoryEventStore(),
                0.5, TestSystems.SHUTDOWN_RESERVE_WH);
        svc.ingestReading(new com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading(
                System.currentTimeMillis(), 200, 50, 30, 35, 40, 20, true));

        PowerRequest req = new PowerRequest("r-clip-i", "launch", 80_000, 100);
        log.info(">>> 用例: 电流折算裁剪 U=200V Imax=300A -> 上限={}W",
                200 * TestSystems.MAX_CURRENT_A);

        PowerDecision d = svc.request(req);

        assertEquals(RequestDecision.CLIPPED, d.decision());
        assertEquals(200 * TestSystems.MAX_CURRENT_A, d.grantedPowerW(), 1e-6);
        assertTrue(d.reasons().contains(RejectReason.BATTERY_DISCHARGE_CURRENT_LIMIT));
        assertFalse(d.reasons().contains(RejectReason.INSUFFICIENT_REMAINING_CAPACITY));
        log.info("<<< 断言通过: granted={}W 原因={}", d.grantedPowerW(), d.reasons());
    }

    @Test
    void duplicateRequestIdIsRejectedAndCreatesNoSecondReservation() {
        PowerCoordinationService svc = readySystem();
        PowerRequest first = new PowerRequest("dup", "throttle", 10_000, 1_000);
        PowerDecision d1 = svc.request(first);
        PowerDecision d2 = svc.request(first);
        log.info(">>> 用例: 重复 requestId 第二次结果={}", d2.decision());

        assertEquals(RequestDecision.ACCEPTED, d1.decision());
        assertEquals(RequestDecision.REJECTED, d2.decision());
        assertEquals(RejectReason.DUPLICATE_REQUEST_ID, d2.reasons().get(0));
        assertEquals(1, svc.activeReservations().size());
        log.info("<<< 断言通过: 活动预留数={}（无重复预留）", svc.activeReservations().size());
    }

    @Test
    void cumulativeGrantsNeverExceedFrozenCapacitySnapshot() throws Exception {
        PowerCoordinationService svc = readySystem();
        // 两笔串行请求验证累计：第一笔冻结后，第二笔基于剩余冻结容量计算
        PowerDecision a = svc.request(new PowerRequest("a", "s1", 30_000, 3_600_000));
        PowerDecision b = svc.request(new PowerRequest("b", "s2", 30_000, 3_600_000));
        log.info(">>> 累计用例: a={}W b={}W", a.grantedPowerW(), b.grantedPowerW());

        assertEquals(30_000, a.grantedPowerW(), 1e-6);
        assertEquals(10_000, b.grantedPowerW(), 1e-6); // 剩余可调度仅 10000Wh/h
        assertEquals(RequestDecision.CLIPPED, b.decision());
        assertEquals(40_000, svc.snapshot().frozenWh(), 1e-6);
        assertEquals(0, svc.snapshot().availableWh(), 1e-6);
        TimeUnit.MILLISECONDS.sleep(1);
        log.info("<<< 断言通过: 累计冻结={}Wh 未超容量", svc.snapshot().frozenWh());
    }
}
