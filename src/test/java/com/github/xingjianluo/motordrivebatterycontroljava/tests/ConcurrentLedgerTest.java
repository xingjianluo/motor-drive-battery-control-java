package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.motordrivebatterycontroljava.device.Battery;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Inverter;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Motor;
import com.github.xingjianluo.motordrivebatterycontroljava.event.FileEventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.PowerLedger;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RejectReason;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RequestDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.support.TestSystems;

/**
 * 并发与重放测试：
 * 1) 多控制入口并发请求，累计授予功率绝不超过同一份冻结容量快照；
 * 2) 并发撤销/结算下无重复释放、无预留泄漏；
 * 3) 文件事件日志重放后剩余能量、冻结与故障状态完全可复现。
 */
@ExtendWith(com.github.xingjianluo.motordrivebatterycontroljava.support.TestCaseResultLogger.class)
class ConcurrentLedgerTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentLedgerTest.class);

    private PowerCoordinationService ready(PowerCoordinationService svc) {
        svc.ingestReading(TestSystems.healthy());
        return svc;
    }

    @Test
    void concurrentRequestsNeverOverCommitFrozenCapacity() throws Exception {
        PowerCoordinationService svc = ready(TestSystems.newService());
        int threads = 32;
        int perThread = 20;
        long durationMs = 3_600_000; // 1h，功率(W) == 能量(Wh)
        // 每笔 3000W：必须 >= 最小可执行功率 1000W，否则会因“低于最小执行门槛”全部被拒
        // （旧用例每笔 500W 时，受理/冻结恒为 0，断言全部恒成立，等于没测）。
        // 理论总需求 32*20*3000=1_920_000Wh，是容量 40000Wh 的 48 倍：
        // 预期在同一份冻结快照上：13 笔全额受理(39000Wh) + 最后 1 笔裁剪到 1000Wh(最小门槛)，
        // 其余 626 笔全部因容量不足被拒；该笔数与线程交叠顺序无关（锁内串行 + 同一账本）。
        double eachPowerW = 3_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<PowerDecision> decisions = java.util.Collections.synchronizedList(new ArrayList<>());

        log.info(">>> 并发受理用例: {} 线程 x {} 请求 x {}W(1h)，容量={}Wh，总需求={}Wh",
                threads, perThread, eachPowerW, TestSystems.CAPACITY_WH,
                threads * perThread * eachPowerW);
        for (int t = 0; t < threads; t++) {
            final String source = "entry-" + t;
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                for (int i = 0; i < perThread; i++) {
                    String id = source + "-r" + i;
                    decisions.add(svc.request(new PowerRequest(id, source, eachPowerW, durationMs)));
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        double totalGrantedWh = decisions.stream()
                .filter(PowerDecision::granted)
                .mapToDouble(PowerDecision::grantedPowerW)
                .sum();
        long accepted = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.ACCEPTED).count();
        long clipped = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.CLIPPED).count();
        long rejected = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.REJECTED).count();
        long rejectedCapacity = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.REJECTED)
                .filter(d -> d.reasons().contains(RejectReason.INSUFFICIENT_REMAINING_CAPACITY))
                .count();

        log.info("受理(ACCEPTED)={}笔 裁剪(CLIPPED)={}笔 拒绝(REJECTED)={}笔(其中容量不足={}笔) "
                        + "累计授予={}Wh 账本冻结={}Wh 物理剩余={}Wh 可调度={}Wh",
                accepted, clipped, rejected, rejectedCapacity,
                totalGrantedWh, svc.snapshot().frozenWh(), svc.snapshot().remainingWh(),
                svc.snapshot().availableWh());

        // ---- 真实业务数值必须对得上，杜绝“全被拒导致断言恒成立” ----
        assertEquals(threads * perThread, decisions.size(), "所有请求都必须有判定结果");
        assertEquals(13, accepted, "3000W*13=39000Wh 应全额受理");
        assertEquals(1, clipped, "第 14 笔只剩 1000Wh 可调度，应裁剪到最小可执行功率");
        assertEquals(626, rejected, "超出容量的其余请求必须被拒绝");
        assertEquals(626, rejectedCapacity, "被拒原因必须是容量不足，而非其它路径");
        assertEquals(14, accepted + clipped, "受理+裁剪合计 14 笔真实走通受理并冻结");

        // 关键不变量：累计授予能量不得超过容量（无瞬时超额）；冻结与授予合计一致
        assertEquals(40_000.0, totalGrantedWh, 1e-6, "累计授予必须恰好打满但不超过容量");
        assertEquals(svc.snapshot().frozenWh(), totalGrantedWh, 1e-6,
                "所有授予功率之和必须等于账本冻结总量");
        assertEquals(40_000.0, svc.snapshot().frozenWh(), 1e-6, "账本应被全额冻结");
        assertTrue(totalGrantedWh <= TestSystems.CAPACITY_WH + 1e-6,
                "累计授予不得超过容量");
        assertTrue(svc.snapshot().availableWh() >= -1e-6, "可调度能量不得为负");
        assertEquals(0.0, svc.snapshot().availableWh(), 1e-6, "打满后可调度应为 0");
        assertEquals(svc.snapshot().remainingWh(),
                svc.snapshot().availableWh() + svc.snapshot().frozenWh()
                        + svc.snapshot().safetyReserveWh(), 1e-6,
                "能量守恒 remaining=available+frozen+reserve");
        log.info("<<< 断言通过: 受理={} 裁剪={} 拒绝={} 无超额、可调度={}Wh、能量守恒",
                accepted, clipped, rejected, svc.snapshot().availableWh());
    }

    @Test
    void concurrentReleasesAreIdempotentWithNoDoubleRefundOrLeak() throws Exception {
        PowerCoordinationService svc = ready(TestSystems.newService());
        // 先建立 30 笔预留，每笔 1000W*1h=1000Wh（恰为最小可执行功率）-> 总冻结 30000Wh
        int n = 30;
        for (int i = 0; i < n; i++) {
            PowerDecision d = svc.request(new PowerRequest("k" + i, "s" + i, 1000, 3_600_000));
            assertEquals(RequestDecision.ACCEPTED, d.decision(),
                    "第 " + i + " 笔被拒: " + d.reasons());
        }
        double frozenBefore = svc.snapshot().frozenWh();
        assertEquals(30_000, frozenBefore, 1e-9);
        log.info(">>> 并发撤销用例: {} 笔预留 冻结={}Wh，每笔被多个线程重复撤销", n, frozenBefore);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        Set<PowerLedger.ReleaseOutcome> outcomes =
                java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (int round = 0; round < 4; round++) {
            for (int i = 0; i < n; i++) {
                final String id = "k" + i;
                final String source = "s" + i;
                pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    outcomes.add(svc.release(id, source));
                });
            }
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        log.info("撤销结果集合={} 冻结={}Wh 活动预留={} 可调度={}Wh",
                outcomes, svc.snapshot().frozenWh(),
                svc.activeReservations().size(), svc.snapshot().availableWh());

        // 恰好每笔 RELEASED 一次，其余都是 ALREADY_RELEASED；无 NOT_FOUND（预留真实存在）
        assertTrue(outcomes.contains(PowerLedger.ReleaseOutcome.RELEASED));
        assertTrue(outcomes.contains(PowerLedger.ReleaseOutcome.ALREADY_RELEASED));
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9, "无预留泄漏");
        assertEquals(0, svc.activeReservations().size());
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().availableWh(), 1e-9);
        log.info("<<< 断言通过: 无重复释放、无泄漏，可调度恢复={}Wh",
                svc.snapshot().availableWh());
    }

    @Test
    void concurrentSettleConsumesPhysicalEnergyExactlyOnce() throws Exception {
        PowerCoordinationService svc = ready(TestSystems.newService());
        int n = 20;
        for (int i = 0; i < n; i++) {
            svc.request(new PowerRequest("c" + i, "s" + i, 1000, 3_600_000)); // 1000Wh each
        }
        assertEquals(20_000, svc.snapshot().frozenWh(), 1e-6);
        log.info(">>> 并发结算用例: {} 笔 x 1000Wh 预留，重复结算同一笔", n);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        Set<PowerLedger.SettleOutcome> outcomes =
                java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < n; i++) {
                final String id = "c" + i;
                final String source = "s" + i;
                pool.submit(() -> {
                    try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    // 每笔实耗 400Wh（少于预留 1000Wh，差额释放）
                    outcomes.add(svc.settle(id, source, 400).outcome());
                });
            }
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        double physical = svc.snapshot().remainingWh();
        log.info("结算结果集合={} 物理剩余={}Wh 冻结={}Wh", outcomes, physical,
                svc.snapshot().frozenWh());

        // 每笔恰好实扣 400Wh 一次：物理剩余 = 40000 - 20*400 = 32000
        assertEquals(TestSystems.CAPACITY_WH - n * 400.0, physical, 1e-6,
                "重复结算不得重复扣减");
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9);
        assertTrue(outcomes.contains(PowerLedger.SettleOutcome.ALREADY_SETTLED));
        log.info("<<< 断言通过: 物理剩余={}Wh（每笔仅扣一次）", physical);
    }

    @Test
    void replayFromFileLogReproducesLedgerFaultStateAndCapacity(@TempDir Path dir) {
        Path logFile = dir.resolve("events.log");
        Battery b1 = TestSystems.battery();
        Inverter i1 = TestSystems.inverter();
        Motor m1 = TestSystems.motor();
        PowerCoordinationService first = ready(new PowerCoordinationService(
                b1, i1, m1, new FileEventStore(logFile), 0.5,
                TestSystems.SHUTDOWN_RESERVE_WH));

        // 场景：受理两笔 -> 撤销一笔 -> 结算一笔 -> 上报一次过流停机（重复上报两次）
        first.request(new PowerRequest("e1", "s1", 10_000, 3_600_000)); // 10000Wh
        first.request(new PowerRequest("e2", "s1", 5_000, 3_600_000));  // 5000Wh
        first.release("e2", "s1");
        first.settle("e1", "s1", 7_000); // 实耗 7000Wh
        var reading = new com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading(
                System.currentTimeMillis(), 360, 400, 30, 35, 40, 400, true);
        first.reportFault(com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType.OVER_CURRENT, reading);
        first.reportFault(com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType.OVER_CURRENT, reading);

        var snap1 = first.snapshot();
        long eventCount = first.eventCount();
        log.info(">>> 重放用例: 事件数={} 物理剩余={}Wh 冻结={}Wh 状态={} 储备={}Wh",
                eventCount, snap1.remainingWh(), snap1.frozenWh(),
                snap1.faultState(), snap1.safetyReserveWh());

        // 用同一日志文件构造全新进程式实例（全新设备模型），重放应得到完全一致的状态
        PowerCoordinationService rebuilt = new PowerCoordinationService(
                TestSystems.battery(), TestSystems.inverter(), TestSystems.motor(),
                new FileEventStore(logFile), 0.5, TestSystems.SHUTDOWN_RESERVE_WH);
        var snap2 = rebuilt.snapshot();

        assertEquals(snap1.faultState(), snap2.faultState());
        assertEquals(snap1.remainingWh(), snap2.remainingWh(), 1e-9);
        assertEquals(snap1.frozenWh(), snap2.frozenWh(), 1e-9);
        assertEquals(snap1.safetyReserveWh(), snap2.safetyReserveWh(), 1e-9);
        assertEquals(snap1.activeFaults(), snap2.activeFaults());
        assertEquals(snap1.ledgerVersion(), snap2.ledgerVersion());
        assertEquals(eventCount, rebuilt.eventCount());
        // 物理剩余 = 40000 - 7000(实耗) = 33000；停机冻结安全储备 200
        assertEquals(33_000, snap2.remainingWh(), 1e-9);
        assertEquals(TestSystems.SHUTDOWN_RESERVE_WH, snap2.safetyReserveWh(), 1e-9);
        assertEquals(0, snap2.frozenWh(), 1e-9);
        log.info("<<< 断言通过: 重放后 物理剩余={}Wh 状态={} 储备={}Wh 完全复现",
                snap2.remainingWh(), snap2.faultState(), snap2.safetyReserveWh());
    }
}
