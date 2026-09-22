package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import com.github.xingjianluo.motordrivebatterycontroljava.power.event.EventLog;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.BatteryModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.InverterModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.LimitBound;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.MotorModel;
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
 * 超额请求、拒绝原因区分、裁剪与冻结剩余容量一致性。
 */
class PowerArbitrationTest {

    private static final Logger log = LoggerFactory.getLogger(PowerArbitrationTest.class);

    private final AtomicLong clock = new AtomicLong(1_000);

    /** 默认模型：350V/60Ah/300A，电流约束功率 105kW；逆变器 80kW；初始 SOC=0.9 */
    private PowerCoordinator newCoordinator(EventLog eventLog, double soc, double inverterW) {
        BatteryModel battery = new BatteryModel(350.0, 60.0, 300.0, soc);
        InverterModel inverter = new InverterModel(inverterW);
        MotorModel motor = new MotorModel(12000, 35.0, inverterW);
        return new PowerCoordinator(battery, inverter, motor, eventLog, 1.0);
    }

    private PowerCoordinator defaultCoordinator(EventLog eventLog) {
        return newCoordinator(eventLog, 0.9, 80000.0);
    }

    private PowerRequest req(String id, double watts) {
        return new PowerRequest(id, "test-entry", watts, clock.incrementAndGet());
    }

    @Test
    void overLimitRequestIsClippedByInverterWithExplicitBound() {
        EventLog events = new InMemoryEventLog();
        PowerCoordinator c = defaultCoordinator(events);

        log.info("用例1 输入：请求 120000W（物理上限：逆变器 80000W、电流折算 105000W、SOC功率 {}W）",
                new BatteryModel(350.0, 60.0, 300.0, 0.9).socPowerLimitW());
        PowerDecision d = c.request(req("r1", 120000));

        assertTrue(d.accepted(), "应按裁剪策略受理，而非拒绝");
        assertTrue(d.clipped(), "120kW > 80kW 必须裁剪");
        assertEquals(80000.0, d.grantedWatts(), 1e-6);
        assertEquals(LimitBound.INVERTER_POWER, d.bindingLimit(), "起作用约束应为逆变器功率");
        assertEquals(80000.0, c.snapshot().ledger().reservedWatts(), 1e-6,
                "裁剪结果须立即占用账本，与冻结剩余容量一致");
        assertEquals(0.0, c.snapshot().ledger().remainingWatts(), 1e-6,
                "已预留合计 80kW，剩余预算应为 0，总线上不得瞬时超额");
        log.info("用例1 判定依据：{}", d.explanation());
        log.info("用例1 状态迁移流水：snapshot={}", c.snapshot());
    }

    @Test
    void secondRequestAfterBudgetFrozenIsRejectedWithDistinguishableReason() {
        EventLog events = new InMemoryEventLog();
        PowerCoordinator c = defaultCoordinator(events);

        c.request(req("r1", 80000));
        log.info("用例2 输入：r1 已冻结 80000W 后再请求 1000W");
        PowerDecision d = c.request(req("r2", 1000));

        assertFalse(d.accepted());
        assertEquals(RejectionReason.BATTERY_BUDGET_EXHAUSTED, d.rejection(),
                "冻结预算已占满时拒绝原因须为 BATTERY_BUDGET_EXHAUSTED");
        assertEquals(LimitBound.LEDGER_BUDGET, d.bindingLimit());
        log.info("用例2 判定依据：{}", d.explanation());
    }

    @Test
    void rejectionReasonsAreDistinguishable() {
        EventLog events = new InMemoryEventLog();

        PowerDecision invalid = defaultCoordinator(events).request(req("x", -5));
        assertEquals(RejectionReason.INVALID_REQUEST, invalid.rejection(), "负功率 -> INVALID_REQUEST");
        log.info("用例3(非法) 判定依据：{}", invalid.explanation());

        PowerCoordinator depleted = newCoordinator(new InMemoryEventLog(), 0.0, 80000.0);
        PowerDecision dep = depleted.request(req("x", 1000));
        assertEquals(RejectionReason.BATTERY_DEPLETED, dep.rejection(), "SOC=0 -> BATTERY_DEPLETED");
        log.info("用例3(耗尽) 判定依据：{}", dep.explanation());

        PowerCoordinator stop = defaultCoordinator(new InMemoryEventLog());
        stop.reportFault(new com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultReport(
                "f1", "oc-1", com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultType.OVER_CURRENT,
                320.0, clock.incrementAndGet()));
        PowerDecision sysStop = stop.request(req("x", 1000));
        assertEquals(RejectionReason.SYSTEM_STOPPED, sysStop.rejection(), "停机 -> SYSTEM_STOPPED");
        log.info("用例3(停机) 判定依据：{}", sysStop.explanation());
    }

    @Test
    void lowSocClipsByBatterySocPower() {
        // SOC=0.1（低于 LOW_SOC=0.2）：SOC 功率 = 105000 * 0.1/0.2 = 52500W
        PowerCoordinator c = newCoordinator(new InMemoryEventLog(), 0.1, 80000.0);
        log.info("用例4 输入：SOC=0.1，请求 80000W（SOC 功率上限 52500W）");
        PowerDecision d = c.request(req("low", 80000));

        assertTrue(d.accepted());
        assertTrue(d.clipped());
        assertEquals(52500.0, d.grantedWatts(), 1e-6);
        assertEquals(LimitBound.BATTERY_SOC_POWER, d.bindingLimit());
        assertEquals(52500.0, c.snapshot().ledger().reservedWatts(), 1e-6);
        log.info("用例4 判定依据：{}", d.explanation());
    }

    @Test
    void grantedPowerNeverExceedsFrozenRemainingAcrossSequentialRequests() {
        PowerCoordinator c = defaultCoordinator(new InMemoryEventLog());
        double total = c.snapshot().ledger().totalBudgetWatts();

        PowerDecision a = c.request(req("a", 30000));
        PowerDecision b = c.request(req("b", 30000));
        PowerDecision e = c.request(req("e", 30000));

        assertEquals(30000.0, a.grantedWatts(), 1e-6);
        assertEquals(30000.0, b.grantedWatts(), 1e-6);
        assertEquals(20000.0, e.grantedWatts(), 1e-6, "第三次只能拿到冻结剩余 20kW");
        assertTrue(e.clipped());
        assertEquals(LimitBound.LEDGER_BUDGET, e.bindingLimit());
        assertEquals(total, c.snapshot().ledger().reservedWatts(), 1e-6,
                "累计授予不得超过冻结总预算");
        log.info("用例5 输入：连续 30000/30000/30000W；判定：30000/30000/{}W；流水：{}",
                e.grantedWatts(), c.snapshot());
    }

    @Test
    void duplicateRequestIdDoesNotDoubleReserve() {
        PowerCoordinator c = defaultCoordinator(new InMemoryEventLog());
        c.request(req("dup", 10000));
        PowerDecision again = c.request(req("dup", 10000));
        assertFalse(again.accepted());
        assertEquals(RejectionReason.INVALID_REQUEST, again.rejection());
        assertEquals(10000.0, c.snapshot().ledger().reservedWatts(), 1e-6,
                "重复 requestId 不得二次占用预算");
        log.info("用例6 判定依据：{}；账本={}", again.explanation(), c.snapshot().ledger());
    }
}
