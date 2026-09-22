package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import com.github.xingjianluo.motordrivebatterycontroljava.power.model.BatteryModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.InverterModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.MotorModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.power.service.PowerCoordinator;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发入口下的功率预留/撤销/结算：单一账本、锁内原子生效、幂等、无泄漏、无超额。
 */
class ConcurrentLedgerTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentLedgerTest.class);

    private PowerCoordinator coordinator() {
        BatteryModel battery = new BatteryModel(350.0, 60.0, 300.0, 0.9);
        InverterModel inverter = new InverterModel(80000.0);
        MotorModel motor = new MotorModel(12000, 35.0, 80000.0);
        return new PowerCoordinator(battery, inverter, motor, new InMemoryEventLog(), 0.01);
    }

    @Test
    void concurrentRequestsNeverOverrunFrozenBudget() throws Exception {
        PowerCoordinator c = coordinator();
        double total = c.snapshot().ledger().totalBudgetWatts();
        int threads = 16;
        int perThread = 10;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong seq = new AtomicLong();
        AtomicInteger accepted = new AtomicInteger();

        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    long id = seq.incrementAndGet();
                    // 每笔 8kW，预算 80kW 最多 10 笔；总需求 1280kW
                    PowerDecision d = c.request(new PowerRequest(
                            "p-" + tid + "-" + i, "entry-" + tid, 8000.0, id));
                    if (d.accepted()) {
                        accepted.incrementAndGet();
                    }
                    // 每次成功后不变式必须成立（不得瞬时超额）
                    var snap = c.snapshot().ledger();
                    assertTrue(snap.reservedWatts() <= total + 1e-6,
                            "已预留不得超过冻结总预算，实际=" + snap.reservedWatts());
                    assertEquals(snap.totalBudgetWatts(),
                            snap.reservedWatts() + snap.remainingWatts(), 1e-6,
                            "totalBudget = reserved + remaining 必须始终成立");
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(10, accepted.get(), "80kW 预算下 8kW 请求只应受理 10 笔");
        assertEquals(total, c.snapshot().ledger().reservedWatts(), 1e-6,
                "10 笔 * 8kW 恰好占满预算，且没有超额");
        log.info("用例1 输入：16 线程 x 10 笔 8000W 请求；受理 {} 笔；最终账本={}", accepted.get(),
                c.snapshot().ledger());
    }

    @Test
    void concurrentReleaseAndSettleAreIdempotentAndLeakFree() throws Exception {
        PowerCoordinator c = coordinator();
        // 先建立 8 笔 10kW 预留
        for (int i = 0; i < 8; i++) {
            PowerDecision d = c.request(new PowerRequest("r" + i, "setup", 10000.0, 100 + i));
            assertTrue(d.accepted(), "初始预留应受理: r" + i);
        }
        assertEquals(8, c.snapshot().ledger().activeReservations());
        double reservedAfterSetup = c.snapshot().ledger().reservedWatts();
        log.info("用例2 输入：8 笔 10000W 预留建立完成，账本={}", c.snapshot().ledger());

        int threads = 16;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new java.util.ArrayList<>();
        // 对每个 requestId：一个撤销者 + 两个重复撤销者，结算同理重复
        for (int i = 0; i < 8; i++) {
            final String id = "r" + i;
            futures.add(pool.submit(() -> {
                start.await();
                c.release(id);
                c.release(id); // 重复撤销：必须空操作
                c.settle(id, 0.0); // 已撤销后结算：必须空操作
                return null;
            }));
            futures.add(pool.submit(() -> {
                start.await();
                c.release(id); // 与上面竞争的重复撤销
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        var snap = c.snapshot().ledger();
        assertEquals(0, snap.activeReservations(), "所有预留必须被释放，无泄漏");
        assertEquals(0.0, snap.reservedWatts(), 1e-6);
        assertEquals(reservedAfterSetup, snap.totalBudgetWatts(), 1e-6,
                "撤销不扣能量，总预算应保持不变（无重复扣减）");
        assertEquals(reservedAfterSetup, snap.remainingWatts(), 1e-6);
        log.info("用例2 流水：并发撤销/重复撤销/重复结算后账本={}", snap);
    }

    @Test
    void settleConsumesBudgetOnceUnderDuplicateSettlements() throws Exception {
        PowerCoordinator c = coordinator();
        double budgetBefore = c.snapshot().ledger().totalBudgetWatts();
        double socBefore = 0.9;
        c.request(new PowerRequest("s1", "setup", 40000.0, 1));

        // 耗能 16000Wh / 21000Wh ≈ 0.762 SOC 下降 -> 结算后 SOC≈0.138，进入低电量段，
        // 物理功率上限变为 SOC 约束 105000 * 0.138/0.2 ≈ 72.5kW < 80kW，冻结预算必须收缩
        double energyWh = 16000.0;

        int threads = 8;
        ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                // 所有线程并发结算同一 requestId：只有一个成功，能量只能扣一次
                if (c.settle("s1", energyWh).isPresent()) {
                    success.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();

        assertEquals(1, success.get(), "同一 requestId 只能结算一次");
        assertEquals(0, c.snapshot().ledger().activeReservations(), "结算后预留必须移除，无泄漏");
        double budgetAfter = c.snapshot().ledger().totalBudgetWatts();
        assertTrue(budgetAfter < budgetBefore,
                "结算耗能进入低电量段后冻结预算必须收缩，实际 " + budgetBefore + " -> " + budgetAfter);
        double expectedSoc = socBefore - energyWh / (350.0 * 60.0);
        double expectedBudget = 350.0 * 300.0 * expectedSoc / 0.2; // 低电量段 SOC 功率约束
        assertEquals(expectedBudget, budgetAfter, 1e-6,
                "能量只扣一次，冻结预算须与结算后的物理上限一致");
        assertEquals(budgetAfter, c.snapshot().ledger().remainingWatts(), 1e-6,
                "无活动预留时剩余预算应等于冻结预算");
        log.info("用例3 输入：8 线程并发结算 s1（耗能 {}Wh）；成功者 {} 个；预算 {}W -> {}W；账本={}",
                energyWh, success.get(), budgetBefore, budgetAfter, c.snapshot().ledger());
    }
}
