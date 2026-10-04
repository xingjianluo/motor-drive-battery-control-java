package com.github.xingjianluo.motordrivebatterycontroljava.tests;

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
import com.github.xingjianluo.motordrivebatterycontroljava.service.SystemSnapshot;
import com.github.xingjianluo.motordrivebatterycontroljava.support.TestSystems;

/**
 * 并发与重放测试：
 * 1) 多控制入口并发请求，累计授予功率绝不超过同一份冻结容量快照；
 * 2) 并发撤销/结算下无重复释放、无预留泄漏；
 * 3) 文件事件日志重放后剩余能量、冻结与故障状态完全可复现。
 */
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
        int totalRequests = threads * perThread; // 640
        long durationMs = 3_600_000; // 1h，功率(W) == 能量(Wh)
        // 每笔 3000W，必须 >= 最小可执行功率 1000W（旧用例用 500W 导致全部因低于门槛被拒，
        // 授予/冻结恒为 0，断言随之恒成立）。
        double eachPowerW = 3_000;
        // 理论总需求 640*3000=1,920,000Wh，远超 40000Wh 容量，必然出现受理+裁剪+拒绝三种结果。
        // 容量 40000Wh / 每笔 3000Wh：前 13 笔全额受理（39000Wh），第 14 笔只剩
        // 1000Wh 可调度 -> 裁剪到 1000W（恰好等于最小门槛，仍可执行），其后全部拒绝。
        // 这些数字与线程调度无关：无论谁先谁后，受理/裁剪/拒绝的笔数与总量都确定。
        final int expectedAccepted = 13;
        final int expectedClipped = 1;
        final int expectedRejected = totalRequests - expectedAccepted - expectedClipped; // 626
        final double expectedFrozenWh = 40_000.0;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<PowerDecision> decisions = java.util.Collections.synchronizedList(new ArrayList<>());

        log.info(">>> 并发受理用例: {} 线程 x {} 请求 x {}W(1h)={}Wh/笔，总需求={}Wh，容量={}Wh，最小可执行={}W",
                threads, perThread, eachPowerW, eachPowerW,
                totalRequests * eachPowerW, TestSystems.CAPACITY_WH, TestSystems.MIN_POWER_W);
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
        assertEquals(totalRequests, decisions.size(), "所有请求都必须返回一笔决策");

        long accepted = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.ACCEPTED).count();
        long clipped = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.CLIPPED).count();
        long rejected = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.REJECTED).count();
        long grantedCount = accepted + clipped;
        double totalGrantedWh = decisions.stream()
                .filter(PowerDecision::granted)
                .mapToDouble(PowerDecision::grantedPowerW)
                .sum();
        // 被裁剪的那笔应正好授予 1000W（=剩余容量，也=最小门槛）
        double clippedGrantWh = decisions.stream()
                .filter(d -> d.decision() == RequestDecision.CLIPPED)
                .mapToDouble(PowerDecision::grantedPowerW).sum();

        SystemSnapshot snap = svc.snapshot();
        log.info("受理(ACCEPTED)={} 裁剪(CLIPPED)={} 拒绝(REJECTED)={} 总计={}",
                accepted, clipped, rejected, decisions.size());
        log.info("累计授予={}Wh（其中裁剪笔授予={}Wh）账本冻结={}Wh 物理剩余={}Wh 可调度={}Wh 安全储备={}Wh",
                totalGrantedWh, clippedGrantWh, snap.frozenWh(),
                snap.remainingWh(), snap.availableWh(), snap.safetyReserveWh());

        // ---- 确定性业务数值断言（不是恒成立断言）：受理/裁剪/拒绝笔数必须精确对得上 ----
        assertEquals(expectedAccepted, accepted,
                "应有 13 笔被 3000Wh 全额受理（实际 " + accepted + "）");
        assertEquals(expectedClipped, clipped,
                "应恰好有 1 笔被裁剪（实际 " + clipped + "）");
        assertEquals(expectedRejected, rejected,
                "超出容量的 626 笔必须被拒绝（实际 " + rejected + "）");
        assertTrue(grantedCount > 0, "必须真实走通受理路径，不能全部被拒");
        assertTrue(rejected > 0, "超出容量的请求必须真实被拒");
        // 每笔全额受理的授予功率必须等于请求值，且有一笔真实发生裁剪
        decisions.stream().filter(d -> d.decision() == RequestDecision.ACCEPTED)
                .forEach(d -> assertEquals(eachPowerW, d.grantedPowerW(), 1e-9,
                        "全额受理授予必须等于请求值: " + d.requestId()));
        assertEquals(1_000, clippedGrantWh, 1e-6,
                "裁剪笔必须真实发生且授予=剩余容量=最小门槛=1000W");
        // 被拒笔授予必须为 0，且原因是容量不足（不是低于最小门槛之类的其它原因）
        decisions.stream().filter(d -> d.decision() == RequestDecision.REJECTED).forEach(d -> {
            assertEquals(0, d.grantedPowerW(), 1e-9, "被拒不得授予能量: " + d.requestId());
            assertTrue(d.reasons().contains(RejectReason.INSUFFICIENT_REMAINING_CAPACITY),
                    "超额拒绝原因必须是容量不足: " + d.requestId() + " " + d.reasons());
        });
        // requestId 唯一：任何一笔不得被当成重复请求
        Set<String> ids = new HashSet<>();
        for (PowerDecision d : decisions) {
            assertTrue(ids.add(d.requestId()), "requestId 重复: " + d.requestId());
        }

        // 关键不变量：累计授予能量必须等于账本冻结总量且不超过容量（无瞬时超额）
        assertEquals(snap.frozenWh(), totalGrantedWh, 1e-6,
                "所有授予功率之和必须等于账本冻结总量");
        assertEquals(expectedFrozenWh, snap.frozenWh(), 1e-6,
                "受理+裁剪必须恰好冻结全部 40000Wh");
        assertTrue(totalGrantedWh <= TestSystems.CAPACITY_WH + 1e-6,
                "累计授予不得超过容量");
        assertTrue(snap.availableWh() >= -1e-6, "可调度能量不得被压成负数");
        assertEquals(0, snap.availableWh(), 1e-6, "容量应被受理+裁剪恰好用尽");
        assertEquals(snap.remainingWh(),
                snap.availableWh() + snap.frozenWh() + snap.safetyReserveWh(), 1e-6,
                "能量守恒 remaining=available+frozen+reserve");
        assertEquals(TestSystems.CAPACITY_WH, snap.remainingWh(), 1e-6,
                "只是冻结尚未结算，物理剩余应保持满电");
        log.info("<<< 断言通过: 受理={} 裁剪={} 拒绝={} 授予=冻结={}Wh 无超额、可调度={}Wh、能量守恒",
                accepted, clipped, rejected, totalGrantedWh, snap.availableWh());
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
