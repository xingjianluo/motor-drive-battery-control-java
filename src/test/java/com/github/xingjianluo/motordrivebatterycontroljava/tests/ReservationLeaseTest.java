package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.motordrivebatterycontroljava.event.FileEventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultState;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.PowerLedger;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.Reservation;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.ReservationStatus;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.service.SystemSnapshot;
import com.github.xingjianluo.motordrivebatterycontroljava.support.MutableTimeSource;
import com.github.xingjianluo.motordrivebatterycontroljava.support.TestSystems;

/**
 * 预留租约测试：到期自动回收、续期边界、与撤销/结算/故障停机同账本口径、
 * 事件重放一致性、并发授予不超额。时间相关行为全部用虚拟时钟推进，无真实 sleep。
 */
class ReservationLeaseTest {

    private static final Logger log = LoggerFactory.getLogger(ReservationLeaseTest.class);

    private static final long T0 = 1_000_000L;
    private static final long ONE_HOUR_MS = 3_600_000L;
    private static final double CAP = TestSystems.CAPACITY_WH;

    private PowerCoordinationService ready(MutableTimeSource clock) {
        PowerCoordinationService svc = TestSystems.newService(
                new com.github.xingjianluo.motordrivebatterycontroljava.event.InMemoryEventStore(),
                clock);
        svc.ingestReading(TestSystems.healthy());
        return svc;
    }

    private static void logSnap(String tag, SystemSnapshot s) {
        log.info("[快照]{} 状态={} 物理剩余={}Wh 冻结={}Wh 可调度={}Wh 活动预留={} 账本版本={}",
                tag, s.faultState(), s.remainingWh(), s.frozenWh(), s.availableWh(),
                s.activeReservations().stream()
                        .map(r -> r.requestId() + "(" + r.status() + ",到期="
                                + r.leaseExpiresAtMs() + ")")
                        .toList(),
                s.ledgerVersion());
    }

    @Test
    void expiredReservationIsAutoReclaimedAndCapacityReturned() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        log.info(">>> 用例[到期自动回收]: 请求 10000W/1h 租约60s，容量={}Wh", CAP);
        PowerDecision d = svc.request(new PowerRequest("r1", "ctrl-a", 10_000, ONE_HOUR_MS, 60_000L));
        log.info("[判定] id={} 结果={} 授予={}W", d.requestId(), d.decision(), d.grantedPowerW());
        assertEquals(10_000, d.grantedPowerW(), 1e-6);
        SystemSnapshot s1 = svc.snapshot();
        logSnap("受理后", s1);
        assertEquals(10_000, s1.frozenWh(), 1e-6);
        assertEquals(CAP - 10_000, s1.availableWh(), 1e-6);

        // 到期前不回收
        clock.advance(59_000);
        SystemSnapshot s2 = svc.snapshot();
        logSnap("t+59s(未到期)", s2);
        assertEquals(10_000, s2.frozenWh(), 1e-6);
        assertEquals(1, s2.activeReservations().size());

        // 到期后自动回收：冻结放回可调度池，不再算活动占用
        clock.advance(2_000); // t+61s，越过 60s 租约
        SystemSnapshot s3 = svc.snapshot();
        logSnap("t+61s(已到期)", s3);
        assertEquals(0, s3.frozenWh(), 1e-6, "到期后冻结必须释放");
        assertEquals(CAP, s3.availableWh(), 1e-6, "冻结容量必须全额放回可调度池");
        assertEquals(CAP, s3.remainingWh(), 1e-6, "回收不消耗物理能量");
        assertTrue(s3.activeReservations().isEmpty(), "回收后不再是活动占用");
        Reservation r = svc.snapshot().activeReservations().stream().findFirst().orElse(null);
        assertTrue(r == null);

        // 回收后撤销/结算都返回可区分结果，且无能量变动
        assertEquals(PowerLedger.ReleaseOutcome.ALREADY_EXPIRED, svc.release("r1", "ctrl-a"));
        assertEquals(PowerLedger.SettleOutcome.ALREADY_EXPIRED,
                svc.settle("r1", "ctrl-a", 5_000).outcome());
        SystemSnapshot s4 = svc.snapshot();
        logSnap("回收后撤销/结算尝试", s4);
        assertEquals(CAP, s4.remainingWh(), 1e-6, "已回收预留不得再扣能量");
        assertEquals(0, s4.frozenWh(), 1e-6, "不得重复释放");
    }

    @Test
    void renewExtendsLeaseWithoutRefreezingOrChangingGrant() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        log.info(">>> 用例[续期]: 请求 8000W/1h 租约60s，t+30s 续期60s");
        PowerDecision d = svc.request(new PowerRequest("r2", "ctrl-a", 8_000, ONE_HOUR_MS, 60_000L));
        assertEquals(8_000, d.grantedPowerW(), 1e-6);
        long originalExpiry = svc.activeReservations().get(0).leaseExpiresAtMs();
        assertEquals(T0 + 60_000, originalExpiry);
        double frozenBefore = svc.snapshot().frozenWh();

        clock.advance(30_000);
        PowerLedger.RenewResult renew = svc.renew("r2", "ctrl-a", 60_000L);
        log.info("[续期] 结果={} 原到期={} 新到期={}", renew.outcome(), originalExpiry,
                renew.newExpiresAtMs());
        assertEquals(PowerLedger.RenewOutcome.RENEWED, renew.outcome());
        assertEquals(originalExpiry + 60_000, renew.newExpiresAtMs());

        SystemSnapshot s = svc.snapshot();
        logSnap("续期后", s);
        assertEquals(frozenBefore, s.frozenWh(), 1e-6, "续期不得重复冻结能量");
        Reservation r = svc.activeReservations().get(0);
        assertEquals(8_000, r.grantedPowerW(), 1e-6, "续期不得改变已授予功率");
        assertEquals(8_000, r.reservedWh(), 1e-6);

        // 原到期时刻之后仍有效（证明续期生效）
        clock.advance(31_000); // t+61s > 原到期 t+60s
        SystemSnapshot s2 = svc.snapshot();
        logSnap("越过原到期(t+61s)", s2);
        assertEquals(1, s2.activeReservations().size(), "续期后越过原到期不得被回收");
        assertEquals(frozenBefore, s2.frozenWh(), 1e-6);

        // 越过新到期时刻后被回收
        clock.advance(60_000); // t+121s > 新到期 t+120s
        SystemSnapshot s3 = svc.snapshot();
        logSnap("越过新到期(t+121s)", s3);
        assertTrue(s3.activeReservations().isEmpty());
        assertEquals(0, s3.frozenWh(), 1e-6);
        assertEquals(CAP, s3.availableWh(), 1e-6);
    }

    @Test
    void renewOnTerminatedOrExpiredOrLeaselessReturnsDistinctOutcome() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        log.info(">>> 用例[续期边界]: 不存在/无租约/已撤销/已结算/已过期 分别续期");
        assertEquals(PowerLedger.RenewOutcome.NOT_FOUND,
                svc.renew("ghost", "ctrl-a", 1_000).outcome());

        svc.request(new PowerRequest("no-lease", "ctrl-a", 5_000, ONE_HOUR_MS));
        assertEquals(PowerLedger.RenewOutcome.NO_LEASE,
                svc.renew("no-lease", "ctrl-a", 1_000).outcome());

        svc.request(new PowerRequest("rel", "ctrl-a", 5_000, ONE_HOUR_MS, 60_000L));
        svc.release("rel", "ctrl-a");
        assertEquals(PowerLedger.RenewOutcome.ALREADY_RELEASED,
                svc.renew("rel", "ctrl-a", 1_000).outcome());

        svc.request(new PowerRequest("st", "ctrl-a", 5_000, ONE_HOUR_MS, 60_000L));
        svc.settle("st", "ctrl-a", 1_000);
        assertEquals(PowerLedger.RenewOutcome.ALREADY_SETTLED,
                svc.renew("st", "ctrl-a", 1_000).outcome());

        svc.request(new PowerRequest("exp", "ctrl-a", 5_000, ONE_HOUR_MS, 30_000L));
        clock.advance(31_000);
        svc.sweepExpired();
        assertEquals(PowerLedger.RenewOutcome.ALREADY_EXPIRED,
                svc.renew("exp", "ctrl-a", 60_000L).outcome());

        // 已过期预留不得被续期重新激活
        SystemSnapshot s = svc.snapshot();
        logSnap("全部边界续期后", s);
        List<String> activeIds = s.activeReservations().stream().map(Reservation::requestId).toList();
        log.info("[校验] 活动预留={}", activeIds);
        assertTrue(!activeIds.contains("exp"), "已过期预留不得重新激活");
        // 仅 no-lease 仍活动（rel 撤销、st 结算、exp 过期）；冻结只有 5000Wh
        assertEquals(List.of("no-lease"), activeIds);
        assertEquals(5_000, s.frozenWh(), 1e-6);
        // 物理剩余只被 st 的实耗 1000Wh 扣减
        assertEquals(CAP - 1_000, s.remainingWh(), 1e-6);
    }

    @Test
    void settleBeforeExpiryConsumesOnlyActualAndNoReclaimAfter() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        log.info(">>> 用例[到期前结算]: 6000W/1h 租约30s，实耗2000Wh 后越过到期");
        svc.request(new PowerRequest("s1", "ctrl-a", 6_000, ONE_HOUR_MS, 30_000L));
        assertEquals(6_000, svc.snapshot().frozenWh(), 1e-6);

        PowerLedger.SettleResult res = svc.settle("s1", "ctrl-a", 2_000);
        assertEquals(PowerLedger.SettleOutcome.SETTLED, res.outcome());
        assertEquals(4_000, res.refundedWh(), 1e-6);
        SystemSnapshot s1 = svc.snapshot();
        logSnap("结算后", s1);
        assertEquals(CAP - 2_000, s1.remainingWh(), 1e-6);
        assertEquals(0, s1.frozenWh(), 1e-6);

        clock.advance(31_000);
        SystemSnapshot s2 = svc.snapshot();
        logSnap("越过到期后", s2);
        assertEquals(CAP - 2_000, s2.remainingWh(), 1e-6, "已结算预留到期后不得再回收/再扣");
        assertEquals(0, s2.frozenWh(), 1e-6);
        assertEquals(CAP - 2_000, s2.availableWh(), 1e-6);
    }

    @Test
    void faultShutdownReleasesLeasedReservationExactlyOnce() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        log.info(">>> 用例[故障停机+租约]: 5000W/1h 租约30s，欠压停机后越过租约");
        svc.request(new PowerRequest("f1", "ctrl-a", 5_000, ONE_HOUR_MS, 30_000L));
        assertEquals(5_000, svc.snapshot().frozenWh(), 1e-6);

        // 欠压 -> SHUTDOWN，活动预留被立即释放
        svc.ingestReading(new SensorReading(clock.nowMs(), 290, 10, 30, 35, 40, 20, true));
        assertEquals(FaultState.SHUTDOWN, svc.faultState());
        SystemSnapshot s1 = svc.snapshot();
        logSnap("停机后", s1);
        assertEquals(0, s1.frozenWh(), 1e-6, "停机必须释放租约预留");
        assertEquals(CAP - TestSystems.SHUTDOWN_RESERVE_WH, s1.availableWh(), 1e-6);

        // 越过租约到期：不得再次回收（已是 RELEASED 终止态），不得重复释放
        clock.advance(31_000);
        List<Reservation> expired = svc.sweepExpired();
        log.info("[校验] 停机后越过租约，回收数={}", expired.size());
        assertTrue(expired.isEmpty(), "停机已释放的预留不得被租约再次回收");
        assertEquals(PowerLedger.ReleaseOutcome.ALREADY_RELEASED, svc.release("f1", "ctrl-a"));
        assertEquals(0, svc.snapshot().frozenWh(), 1e-6);
        assertEquals(CAP, svc.snapshot().remainingWh(), 1e-6, "全程不得有能量实扣");
    }

    @Test
    void replayRestoresLeaseAndReclaimState(@TempDir Path dir) {
        MutableTimeSource clock = new MutableTimeSource(T0);
        FileEventStore store = new FileEventStore(dir.resolve("events.log"));
        PowerCoordinationService svc = TestSystems.newService(store, clock);
        svc.ingestReading(TestSystems.healthy());

        log.info(">>> 用例[重放一致性]: 预留x3(续期/过期/结算) 后重建系统对比");
        svc.request(new PowerRequest("keep", "ctrl-a", 7_000, ONE_HOUR_MS, 120_000L));
        svc.request(new PowerRequest("renewed", "ctrl-a", 6_000, ONE_HOUR_MS, 60_000L));
        svc.request(new PowerRequest("gone", "ctrl-a", 5_000, ONE_HOUR_MS, 30_000L));
        svc.request(new PowerRequest("done", "ctrl-a", 4_000, ONE_HOUR_MS, 90_000L));
        svc.request(new PowerRequest("plain", "ctrl-a", 3_000, ONE_HOUR_MS)); // 无租约

        clock.advance(10_000);
        svc.renew("renewed", "ctrl-a", 120_000L);   // 续期到 t+180s
        svc.settle("done", "ctrl-a", 1_500);        // 结算实耗 1500Wh
        clock.advance(25_000);                      // t+35s：gone(30s) 到期
        svc.sweepExpired();

        SystemSnapshot before = svc.snapshot();
        logSnap("停机前", before);
        assertEquals(7_000 + 6_000 + 3_000, before.frozenWh(), 1e-6);
        assertEquals(CAP - 1_500, before.remainingWh(), 1e-6);

        // 模拟进程重启：同一事件日志 + 同一时间源重建
        PowerCoordinationService restored = TestSystems.newService(
                new FileEventStore(dir.resolve("events.log")), clock);
        SystemSnapshot after = restored.snapshot();
        logSnap("重放后", after);

        assertEquals(before.remainingWh(), after.remainingWh(), 1e-6, "重放后物理剩余必须一致");
        assertEquals(before.frozenWh(), after.frozenWh(), 1e-6, "重放后冻结必须一致");
        assertEquals(before.availableWh(), after.availableWh(), 1e-6, "重放后可调度必须一致");
        assertEquals(before.activeReservations().stream().map(Reservation::requestId).sorted().toList(),
                after.activeReservations().stream().map(Reservation::requestId).sorted().toList(),
                "重放后活动预留集合必须一致");
        // 已发生的回收结果可复现：gone 为 EXPIRED 终止态，再撤销返回 ALREADY_EXPIRED
        assertEquals(PowerLedger.ReleaseOutcome.ALREADY_EXPIRED, restored.release("gone", "ctrl-a"));
        assertEquals(PowerLedger.SettleOutcome.ALREADY_SETTLED, restored.settle("done", "ctrl-a", 1).outcome());
        // 续期结果可复现：renewed 的到期时刻 = t+180s
        Reservation renewed = restored.activeReservations().stream()
                .filter(r -> r.requestId().equals("renewed")).findFirst().orElseThrow();
        assertEquals(T0 + 180_000, renewed.leaseExpiresAtMs());

        // 重放后继续推进时钟：keep(t+120s) 到期被回收，renewed(t+180s) 仍在
        clock.advance(90_000); // t+125s
        SystemSnapshot s2 = restored.snapshot();
        logSnap("重放后推进到t+125s", s2);
        List<String> active = s2.activeReservations().stream().map(Reservation::requestId).sorted().toList();
        assertEquals(List.of("plain", "renewed"), active);
        assertEquals(6_000 + 3_000, s2.frozenWh(), 1e-6);
    }

    @Test
    void concurrentLeasedRequestsNeverOverCommitAndReclaimRestoresCapacity() throws Exception {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        int threads = 16;
        int perThread = 10;
        double eachPowerW = 1_000;            // 1h -> 1000Wh/笔（=最小可执行功率，确保走授予路径）
        long leaseMs = 300_000;
        int total = threads * perThread;      // 160 笔，理论需求 160000Wh > 40000Wh
        log.info(">>> 用例[并发租约]: {}线程x{}笔x{}W(1h,租约{}s)，容量={}Wh",
                threads, perThread, eachPowerW, leaseMs / 1000, CAP);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<PowerDecision> decisions = Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int k = 0; k < perThread; k++) {
                        PowerDecision d = svc.request(new PowerRequest(
                                "c-" + tid + "-" + k, "ctrl-" + tid,
                                eachPowerW, ONE_HOUR_MS, leaseMs));
                        decisions.add(d);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long granted = decisions.stream().filter(PowerDecision::granted).count();
        long rejected = total - granted;
        double grantedPowerSum = decisions.stream().mapToDouble(PowerDecision::grantedPowerW).sum();
        SystemSnapshot s1 = svc.snapshot();
        log.info("[流水] 受理={} 拒绝={} 累计授予={}W 账本冻结={}Wh 可调度={}Wh",
                granted, rejected, grantedPowerSum, s1.frozenWh(), s1.availableWh());
        decisions.stream().filter(d -> !d.granted()).limit(3)
                .forEach(d -> log.info("[拒绝样本] id={} 原因={}", d.requestId(), d.reasons()));

        // 确定性断言：40000Wh / 1000Wh = 恰好 40 笔全额受理
        assertEquals(40, granted, "受理数必须等于容量整除单笔冻结");
        assertEquals(120, rejected);
        assertEquals(40 * eachPowerW, grantedPowerSum, 1e-6);
        assertEquals(CAP, s1.frozenWh(), 1e-6, "冻结量必须等于全部容量");
        assertEquals(0, s1.availableWh(), 1e-6, "可调度必须被占满，累计授予不超容量");

        // 并发续期全部活动预留：只延长有效期，冻结不变
        List<String> activeIds = svc.activeReservations().stream().map(Reservation::requestId).toList();
        assertEquals(40, activeIds.size());
        ExecutorService pool2 = Executors.newFixedThreadPool(threads);
        List<PowerLedger.RenewOutcome> renewOutcomes = Collections.synchronizedList(new ArrayList<>());
        for (String id : activeIds) {
            String source = "ctrl-" + id.split("-")[1]; // id 形如 c-{tid}-{k}
            pool2.submit(() -> renewOutcomes.add(svc.renew(id, source, leaseMs).outcome()));
        }
        pool2.shutdown();
        assertTrue(pool2.awaitTermination(30, TimeUnit.SECONDS));
        long renewedCount = renewOutcomes.stream()
                .filter(o -> o == PowerLedger.RenewOutcome.RENEWED).count();
        log.info("[流水] 并发续期：成功={} 总数={}", renewedCount, renewOutcomes.size());
        assertEquals(40, renewedCount, "全部活动预留必须续期成功");
        assertEquals(CAP, svc.snapshot().frozenWh(), 1e-6, "续期不得重复冻结");

        // 越过原到期(t+300s)但未到续期后到期(t+600s)：不得回收
        clock.advance(leaseMs + 1_000);
        SystemSnapshot s2 = svc.snapshot();
        logSnap("越过原到期", s2);
        assertEquals(40, s2.activeReservations().size(), "续期保护内不得回收");
        assertEquals(CAP, s2.frozenWh(), 1e-6);

        // 越过续期后到期：全部自动回收，容量完整回到可调度池
        clock.advance(leaseMs);
        SystemSnapshot s3 = svc.snapshot();
        logSnap("越过续期后到期", s3);
        assertTrue(s3.activeReservations().isEmpty(), "到期后不得有预留泄漏");
        assertEquals(0, s3.frozenWh(), 1e-6);
        assertEquals(CAP, s3.availableWh(), 1e-6, "回收后容量必须完整恢复");
        assertEquals(CAP, s3.remainingWh(), 1e-6, "全程无实耗，物理剩余不变");

        // 容量恢复后可再次受理（证明回收真正放回可调度池）
        PowerDecision again = svc.request(new PowerRequest("again", "ctrl-a", 10_000, ONE_HOUR_MS, 60_000L));
        log.info("[判定] 回收后再请求 10000W -> {} 授予={}W", again.decision(), again.grantedPowerW());
        assertTrue(again.granted());
        assertEquals(10_000, again.grantedPowerW(), 1e-6);
    }
}
