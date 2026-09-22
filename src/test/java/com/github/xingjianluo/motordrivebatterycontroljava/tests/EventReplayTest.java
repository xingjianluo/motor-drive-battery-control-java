package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import com.github.xingjianluo.motordrivebatterycontroljava.power.event.EventLog;
import com.github.xingjianluo.motordrivebatterycontroljava.power.event.FileEventLog;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.BatteryModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.InverterModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.MotorModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.OperationalState;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.power.service.PowerCoordinator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程重启 / 事件重放：剩余容量（账本 + SOC）、故障状态、活动预留必须可复现。
 */
class EventReplayTest {

    private static final Logger log = LoggerFactory.getLogger(EventReplayTest.class);
    private final AtomicLong clock = new AtomicLong(5_000);

    private BatteryModel battery() {
        return new BatteryModel(350.0, 60.0, 300.0, 0.9);
    }

    private InverterModel inverter() {
        return new InverterModel(80000.0);
    }

    private MotorModel motor() {
        return new MotorModel(12000, 35.0, 80000.0);
    }

    private void driveScenario(PowerCoordinator c) {
        c.request(new PowerRequest("r1", "entry-a", 30000.0, clock.incrementAndGet()));
        c.request(new PowerRequest("r2", "entry-b", 20000.0, clock.incrementAndGet()));
        c.release("r1");
        // r2 结算，耗能 16000Wh，SOC 0.9 -> ~0.138（低电量段）
        c.settle("r2", 16000.0);
        c.reportFault(new com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultReport(
                "p1", "ot-1", FaultType.OVER_TEMPERATURE, 95.0, clock.incrementAndGet()));
        // 新一笔预留建立在结算后的冻结预算与降级系数之上：min(预算, 物理上限*0.5)
        c.request(new PowerRequest("r3", "entry-a", 100000.0, clock.incrementAndGet()));
    }

    private void assertScenarioReproduced(PowerCoordinator c, String tag) {
        var snap = c.snapshot();
        assertEquals(OperationalState.DERATED, snap.state(), tag + ": 过温降级状态必须复现");
        assertTrue(snap.activeFaults().containsKey("ot-1"), tag + ": 活动故障必须复现");
        assertEquals(1, snap.ledger().activeReservations(), tag + ": 仅 r3 应存活");

        double expectedSoc = 0.9 - 16000.0 / 21000.0;
        double expectedPhysical = 350.0 * 300.0 * expectedSoc / 0.2; // ≈72500W
        double expectedGranted = expectedPhysical * 0.5;
        assertEquals(expectedGranted, snap.ledger().reservedWatts(), 1e-6,
                tag + ": r3 是按结算后预算 + 降级系数 0.5 裁剪授予的");
        assertEquals(expectedPhysical - expectedGranted, snap.ledger().remainingWatts(), 1e-6,
                tag + ": 剩余容量必须与冻结预算一致");
        log.info("{} 重放快照：state={} 账本={} 活动故障={}", tag, snap.state(), snap.ledger(), snap.activeFaults());
    }

    @Test
    void replayReproducesLedgerAndFaultStateWithInMemoryLog() {
        EventLog events = new InMemoryEventLog();
        PowerCoordinator first = new PowerCoordinator(battery(), inverter(), motor(), events, 1.0);
        driveScenario(first);
        var before = first.snapshot();
        log.info("重放前快照：state={} 账本={} 活动故障={}", before.state(), before.ledger(), before.activeFaults());

        // 模拟进程重启：全新模型 + 同一份事件日志
        PowerCoordinator restored = new PowerCoordinator(battery(), inverter(), motor(), events, 1.0);
        restored.replay();
        assertScenarioReproduced(restored, "内存日志");
    }

    @Test
    void replayFromFileLogSurvivesRestart(@TempDir Path tempDir) {
        Path logFile = tempDir.resolve("power-events.log");

        PowerCoordinator first = new PowerCoordinator(battery(), inverter(), motor(),
                new FileEventLog(logFile), 1.0);
        driveScenario(first);

        PowerCoordinator restored = new PowerCoordinator(battery(), inverter(), motor(),
                new FileEventLog(logFile), 1.0);
        restored.replay();
        assertScenarioReproduced(restored, "文件日志");

        // 重放后继续工作并再次"重启"，结果仍一致（重放幂等，事件不重复生效）
        restored.request(new PowerRequest("r4", "entry-c", 5000.0, clock.incrementAndGet()));
        PowerCoordinator third = new PowerCoordinator(battery(), inverter(), motor(),
                new FileEventLog(logFile), 1.0);
        third.replay();
        var snap = third.snapshot();
        assertEquals(2, snap.ledger().activeReservations(), "r3 与 r4 都应存活，事件不重复生效");
        log.info("二次重放快照：账本={} 状态={}", snap.ledger(), snap.state());
    }
}
