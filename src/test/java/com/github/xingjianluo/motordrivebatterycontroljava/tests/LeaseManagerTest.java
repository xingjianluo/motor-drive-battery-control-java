package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.FileEventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.InMemoryEventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultState;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.PowerLedger;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.Reservation;
import com.github.xingjianluo.motordrivebatterycontroljava.ledger.ReservationStatus;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.model.RequestDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.support.TestSystems;
import com.github.xingjianluo.motordrivebatterycontroljava.time.MutableTimeSource;

/**
 * 预留租约：有效期、到期自动回收、续期边界、回收后不可重新激活。
 * 所有时间行为基于可控逻辑时钟，无需真实 sleep 即可稳定复现。
 */
class LeaseManagerTest {

    private static final Logger log = LoggerFactory.getLogger(LeaseManagerTest.class);

    private static final long T0 = 1_000_000L;
    private static final long HOUR = 3_600_000L;

    private PowerCoordinationService ready(MutableTimeSource clock) {
        PowerCoordinationService svc = TestSystems.newService(clock);
        svc.ingestReading(TestSystems.healthy());
        return svc;
    }

    private PowerDecision lease(PowerCoordinationService svc, String id, String source,
                                double powerW, long durationMs, long leaseMs) {
        return svc.request(new PowerRequest(id, source, powerW, durationMs, leaseMs));
    }

    @Test
    void expiredReservationIsReapedAndCapacityReturnsWithoutSettleOrRelease() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        PowerDecision d = lease(svc, "L1", "entry-a", 10_000, HOUR, 2_000);
        assertEquals(RequestDecision.ACCEPTED, d.decision(), "应全额受理: " + d.message());
        log.info(">>> 过期回收用例: t={} 受理 L1 授予={}W 冻结={}Wh 可调度={}Wh",
                clock.nowMs(), d.grantedPowerW(), svc.snapshot().frozenWh(),
                svc.snapshot().availableWh());
        assertEquals(10_000, svc.snapshot().frozenWh(), 1e-9);
        assertEquals(30_000, svc.snapshot().availableWh(), 1e-9);
        assertEquals(1, svc.activeReservations().size());

        // 到期前一刻：仍是活动占用
        clock.advance(1_999);
        assertEquals(1, svc.activeReservations().size(), "到期前不应被回收");
        assertEquals(10_000, svc.snapshot().frozenWh(), 1e-9);
        log.info("[流水] t={} 距到期 1ms，活动预留={} 冻结={}Wh",
                clock.nowMs(), svc.activeReservations().size(), svc.snapshot().frozenWh());

        // 到期：显式触发回收（生产环境由后台回收器/任一业务入口自动完成）
        clock.advance(1);
        var reaped = svc.reapExpiredReservations();
        log.info("[流水] t={} 到期回收 {} 笔: {}", clock.nowMs(), reaped.size(),
                reaped.stream().map(r -> r.requestId() + "(" + r.reservedWh() + "Wh)").toList());
        assertEquals(1, reaped.size());
        assertEquals(ReservationStatus.EXPIRED, reaped.get(0).status());
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9, "回收后冻结必须清零");
        assertEquals(0, svc.activeReservations().size(), "回收后不再是活动占用");
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().availableWh(), 1e-9,
                "冻结容量必须回到可调度池");
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().remainingWh(), 1e-9,
                "回收不动物理剩余（能量本未实耗）");

        // 回收幂等：再次回收不产生重复事件、不重复释放
        var second = svc.reapExpiredReservations();
        assertEquals(0, second.size(), "已回收预留不得再次回收");
        log.info("<<< 断言通过: 到期自动回收，容量归还={}Wh，重复回收无动作",
                svc.snapshot().availableWh());
    }

    @Test
    void expiryIsAlsoTriggeredByAnyBusinessEntryWithoutManualReap() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        lease(svc, "L2", "entry-a", 8_000, HOUR, 5_000); // 8000Wh
        assertEquals(8_000, svc.snapshot().frozenWh(), 1e-9);

        clock.advance(5_001);
        // 不手动 reap：用一笔新请求进入入口，入口内部必须先完成回收
        PowerDecision d2 = lease(svc, "L3", "entry-b", 40_000, HOUR, 10_000);
        log.info(">>> 入口联动回收用例: t={} L2 到期后直接受理 L3 -> {} 授予={}W 冻结={}Wh",
                clock.nowMs(), d2.decision(), d2.grantedPowerW(), svc.snapshot().frozenWh());
        assertEquals(RequestDecision.ACCEPTED, d2.decision(),
                "回收释放 8000Wh 后容量应足够全额受理 40000W: " + d2.message());
        assertEquals(40_000, svc.snapshot().frozenWh(), 1e-9, "只应有 L3 一笔冻结");
        assertEquals(1, svc.activeReservations().size());
        assertEquals(1, svc.activeReservations().stream()
                .filter(r -> r.requestId().equals("L3")).count(), "EXPIRED 的 L2 不应出现在活动列表");
        assertTrue(svc.activeReservations().stream().noneMatch(r -> r.requestId().equals("L2")));
        log.info("<<< 断言通过: 业务入口自动完成回收，L3 授予={}W", d2.grantedPowerW());
    }

    @Test
    void renewExtendsLeaseWithoutRefreezingOrChangingGrant() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        PowerDecision d = lease(svc, "R1", "entry-a", 6_000, HOUR, 3_000);
        long firstExpiry = T0 + 3_000;
        Reservation before = svc.activeReservations().get(0);
        assertEquals(firstExpiry, before.expiresAtEpochMs());
        assertEquals(6_000, before.grantedPowerW(), 1e-9);

        // 走到原到期时刻前续期 5000ms
        clock.advance(2_000);
        var rr = svc.renew("R1", "entry-a", 5_000);
        log.info(">>> 续期用例: t={} 首次续期结果={} 到期={} (预期 {})",
                clock.nowMs(), rr.outcome(), rr.expiresAtEpochMs(), firstExpiry + 5_000);
        assertEquals(PowerLedger.RenewOutcome.RENEWED, rr.outcome());
        assertEquals(firstExpiry + 5_000, rr.expiresAtEpochMs());
        assertEquals(6_000, svc.snapshot().frozenWh(), 1e-9, "续期不得重复冻结");
        Reservation after = svc.activeReservations().get(0);
        assertEquals(6_000, after.grantedPowerW(), 1e-9, "续期不得改变授予功率");

        // 越过原始到期点：因为续期，预留必须仍然活动
        clock.advance(2_000); // t=T0+4000 > 原到期 T0+3000
        svc.reapExpiredReservations();
        assertEquals(1, svc.activeReservations().size(), "续期后原到期点不应回收");
        assertEquals(6_000, svc.snapshot().frozenWh(), 1e-9);

        // 再次续期：从当前时间(更晚)与旧到期取较大者
        var rr2 = svc.renew("R1", "entry-a", 1_000);
        assertEquals(PowerLedger.RenewOutcome.RENEWED, rr2.outcome());
        assertEquals(firstExpiry + 5_000 + 1_000, rr2.expiresAtEpochMs(),
                "仍在旧有效期内续期应在旧到期点上累加");
        assertEquals(6_000, svc.snapshot().frozenWh(), 1e-9, "多次续期仍只冻结一次");
        log.info("<<< 断言通过: 多次续期仅延长有效期，冻结恒为={}Wh 授予={}W",
                svc.snapshot().frozenWh(), after.grantedPowerW());
        // 一旦越过最新到期点，续期只能得到 ALREADY_EXPIRED 且不重新激活
        clock.setTime(firstExpiry + 6_500);
        var rr3 = svc.renew("R1", "entry-a", 1_000);
        assertEquals(PowerLedger.RenewOutcome.ALREADY_EXPIRED, rr3.outcome());
        assertEquals(0, svc.activeReservations().size());
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9);
    }

    @Test
    void renewAfterExpiryOrFinishReturnsDistinctOutcomeAndNeverReactivates() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);

        // 1) 过期后续期 -> ALREADY_EXPIRED，不重新激活
        lease(svc, "X1", "s", 2_000, HOUR, 1_000);
        clock.advance(1_001);
        var expired = svc.renew("X1", "s", 5_000);
        log.info(">>> 续期边界用例: 过期后续期结果={}", expired.outcome());
        assertEquals(PowerLedger.RenewOutcome.ALREADY_EXPIRED, expired.outcome());
        assertEquals(0, svc.activeReservations().size());
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9);
        // 再续期仍然是 ALREADY_EXPIRED
        var expiredAgain = svc.renew("X1", "s", 5_000);
        assertEquals(PowerLedger.RenewOutcome.ALREADY_EXPIRED, expiredAgain.outcome());
        // 撤销/结算一笔已回收预留也必须被拒绝
        assertEquals(PowerLedger.ReleaseOutcome.ALREADY_EXPIRED, svc.release("X1", "s"));
        assertEquals(PowerLedger.SettleOutcome.ALREADY_EXPIRED,
                svc.settle("X1", "s", 100).outcome());
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().remainingWh(), 1e-9,
                "回收后再结算不得扣减物理能量");

        // 2) 已撤销后续期 -> ALREADY_RELEASED
        lease(svc, "X2", "s", 2_000, HOUR, 100_000);
        assertEquals(PowerLedger.ReleaseOutcome.RELEASED, svc.release("X2", "s"));
        var released = svc.renew("X2", "s", 1_000);
        assertEquals(PowerLedger.RenewOutcome.ALREADY_RELEASED, released.outcome());

        // 3) 已结算后续期 -> ALREADY_SETTLED
        lease(svc, "X3", "s", 2_000, HOUR, 100_000); // 2000Wh
        svc.settle("X3", "s", 500);
        var settled = svc.renew("X3", "s", 1_000);
        log.info("[流水] 撤销后续期={} 结算后续期={}", released.outcome(), settled.outcome());
        assertEquals(PowerLedger.RenewOutcome.ALREADY_SETTLED, settled.outcome());

        // 4) 不存在/来源不符 -> NOT_FOUND
        assertEquals(PowerLedger.RenewOutcome.NOT_FOUND, svc.renew("nope", "s", 1_000).outcome());
        assertEquals(PowerLedger.RenewOutcome.NOT_FOUND, svc.renew("X3", "other", 1_000).outcome());

        assertEquals(0, svc.activeReservations().size(), "任何终止态都不得被续期重新激活");
        log.info("<<< 断言通过: 终止态续期结果可区分且不重新激活，冻结={}Wh 物理剩余={}Wh",
                svc.snapshot().frozenWh(), svc.snapshot().remainingWh());
    }

    @Test
    void requestWithoutLeaseNeverExpiresAndStaysBackwardCompatible() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        // 走旧构造器（无 leaseMs）+ 显式 null
        PowerDecision d1 = svc.request(new PowerRequest("N1", "s", 5_000, HOUR));
        PowerDecision d2 = svc.request(new PowerRequest("N2", "s", 5_000, HOUR, null));
        log.info(">>> 兼容用例: 无有效期请求 {} / {}", d1.decision(), d2.decision());
        assertEquals(RequestDecision.ACCEPTED, d1.decision());
        assertEquals(RequestDecision.ACCEPTED, d2.decision());

        // 时间推进再久也不回收
        clock.advance(365 * 24 * HOUR);
        assertEquals(0, svc.reapExpiredReservations().size());
        assertEquals(2, svc.activeReservations().size());
        assertEquals(10_000, svc.snapshot().frozenWh(), 1e-9);
        assertFalse(svc.activeReservations().get(0).hasLease());

        // 对无有效期预留续期：语义不变（仍永不过期），不动账
        var rr = svc.renew("N1", "s", 1_000);
        assertEquals(PowerLedger.RenewOutcome.RENEWED, rr.outcome());
        assertEquals(Reservation.NEVER_EXPIRES, rr.expiresAtEpochMs());
        assertEquals(10_000, svc.snapshot().frozenWh(), 1e-9);

        // 撤销/结算仍按旧口径正常工作
        assertEquals(PowerLedger.ReleaseOutcome.RELEASED, svc.release("N1", "s"));
        assertEquals(PowerLedger.SettleOutcome.SETTLED,
                svc.settle("N2", "s", 3_000).outcome());
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9);
        assertEquals(TestSystems.CAPACITY_WH - 3_000, svc.snapshot().remainingWh(), 1e-9);
        log.info("<<< 断言通过: 不带有效期的预留行为与旧版本一致，物理剩余={}Wh",
                svc.snapshot().remainingWh());
    }

    @Test
    void reapedCapacityIsImmediatelyReusableByNewGrants() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        // 两笔各 20000Wh(1h)，占满 40000Wh（忽略安全储备，非停机态为 0）
        lease(svc, "P1", "s", 20_000, HOUR, 2_000);
        PowerDecision second = lease(svc, "P2", "s", 20_000, HOUR, 10_000);
        PowerDecision extra = lease(svc, "P3", "s", 10_000, HOUR, 10_000);
        log.info(">>> 容量再利用用例: 第二笔={} 授予={}W 容量满后第三笔={} 授予={}W",
                second.decision(), second.grantedPowerW(), extra.decision(), extra.grantedPowerW());
        assertEquals(RequestDecision.ACCEPTED, second.decision());
        assertEquals(RequestDecision.REJECTED, extra.decision(), "容量占满时必须拒绝");
        assertEquals(0, extra.grantedPowerW(), 1e-9);
        assertEquals(40_000, svc.snapshot().frozenWh(), 1e-9);

        // P1 到期回收后，其 20000Wh 必须立刻可被新请求重新调度
        clock.advance(2_001);
        PowerDecision reuse = lease(svc, "P4", "s", 20_000, HOUR, 10_000);
        log.info("[流水] t={} P1 回收后受理 P4 -> {} 授予={}W 冻结={}Wh 活动数={}",
                clock.nowMs(), reuse.decision(), reuse.grantedPowerW(),
                svc.snapshot().frozenWh(), svc.activeReservations().size());
        assertEquals(RequestDecision.ACCEPTED, reuse.decision(),
                "回收容量必须可重新调度: " + reuse.message());
        assertEquals(20_000, reuse.grantedPowerW(), 1e-9);
        assertEquals(40_000, svc.snapshot().frozenWh(), 1e-9, "P2+P4 合计仍不得超过容量");
        assertTrue(svc.snapshot().availableWh() >= -1e-6);
        // 物理剩余未动（还没结算）
        assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().remainingWh(), 1e-9);
        log.info("<<< 断言通过: 回收容量被重新授予，累计授予仍不超容量");
    }

    @Test
    void concurrentExpiryVsSettleAndReleaseNeverDoubleRefunds() throws Exception {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        int n = 30;
        long leaseMs = 1_000;
        for (int i = 0; i < n; i++) {
            // 每笔 1000W*1h = 1000Wh
            PowerDecision d = lease(svc, "Q" + i, "s" + i, 1000, HOUR, leaseMs);
            assertEquals(RequestDecision.ACCEPTED, d.decision(), d.message());
        }
        assertEquals(30_000, svc.snapshot().frozenWh(), 1e-9);
        log.info(">>> 并发到期竞争用例: {} 笔 x 1000Wh 租约={}ms，结算/撤销/时钟推进+回收 同时竞争",
                n, leaseMs);

        // 不预先推进时钟：15 笔结算、10 笔撤销、5 个回收线程在同一闸口并发；
        // 回收线程内部推进逻辑时钟越过到期点。哪些笔被结算、哪些被撤销、哪些过期不确定，
        // 但“每笔恰好终结一次、不重复扣减/释放”的不变量恒定。
        ExecutorService pool = Executors.newFixedThreadPool(24);
        CountDownLatch startGate = new CountDownLatch(1);
        java.util.concurrent.ConcurrentHashMap<String, String> firstTerminal =
                new java.util.concurrent.ConcurrentHashMap<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            if (idx < 15) {
                pool.submit(() -> {
                    awaitGate(startGate);
                    String id = "Q" + idx;
                    var o = svc.settle(id, "s" + idx, 400).outcome();
                    firstTerminal.putIfAbsent(id, "SETTLED:" + o);
                });
            } else if (idx < 25) {
                pool.submit(() -> {
                    awaitGate(startGate);
                    String id = "Q" + idx;
                    var o = svc.release(id, "s" + idx);
                    firstTerminal.putIfAbsent(id, "RELEASED:" + o);
                });
            } else {
                pool.submit(() -> {
                    awaitGate(startGate);
                    clock.advance(leaseMs + 1);
                    svc.reapExpiredReservations();
                });
            }
        }
        startGate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long settled = firstTerminal.values().stream().filter("SETTLED:SETTLED"::equals).count();
        long released = firstTerminal.values().stream().filter("RELEASED:RELEASED"::equals).count();
        long expired = firstTerminal.values().stream()
                .filter(v -> v.endsWith("ALREADY_EXPIRED")).count();
        log.info("[流水] 观察到终结: 结算={} 撤销={} 因过期未得手={} 冻结={}Wh 活动={} 物理剩余={}Wh",
                settled, released, expired,
                svc.snapshot().frozenWh(), svc.activeReservations().size(),
                svc.snapshot().remainingWh());
        assertTrue(settled + released + expired <= n, "观察到的终结笔数不得超过总笔数");

        assertEquals(0, svc.snapshot().frozenWh(), 1e-9, "无预留泄漏");
        assertEquals(0, svc.activeReservations().size());
        // 物理剩余只能由“真正结算成功”的笔扣减：每笔 400Wh 且仅扣一次
        assertEquals(TestSystems.CAPACITY_WH - settled * 400.0,
                svc.snapshot().remainingWh(), 1e-6, "只有结算实扣，回收/撤销不动物理剩余");
        assertEquals(svc.snapshot().remainingWh(),
                svc.snapshot().availableWh() + svc.snapshot().frozenWh()
                        + svc.snapshot().safetyReserveWh(), 1e-6, "能量守恒");
        // 必须真实走到授予结算路径，也必须真实走到到期回收路径
        assertTrue(settled > 0, "必须有预留真实走到结算扣减路径");
        assertTrue(settled < 15, "到期竞争下 15 笔结算不应全部得手（否则未测到回收竞争）");
        log.info("<<< 断言通过: 到期与结算/撤销交叠下 结算={}，无双重释放、无泄漏、无超额", settled);
    }

    private static void awaitGate(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Test
    void replayAfterRestartReproducesExpiriesAndRemainingLeases(@TempDir Path dir) {
        MutableTimeSource clock1 = new MutableTimeSource(T0);
        Path logFile = dir.resolve("lease-events.log");
        EventStore store = new FileEventStore(logFile);
        PowerCoordinationService first = TestSystems.newService(clock1, store);
        first.ingestReading(TestSystems.healthy());

        // E1 短租约(到期回收) E2 长租约(重启后仍有效) E3 续期一次(到期延后)
        lease(first, "E1", "s", 5_000, HOUR, 1_000); // 5000Wh
        lease(first, "E2", "s", 4_000, HOUR, 100_000); // 4000Wh
        lease(first, "E3", "s", 3_000, HOUR, 2_000); // 3000Wh
        first.renew("E3", "s", 8_000); // E3 到期 -> T0+10000
        clock1.advance(1_001);
        var reaped = first.reapExpiredReservations(); // E1 回收
        first.settle("E2", "s", 1_000); // E2 结算（终结，租约不再有意义）

        var snap1 = first.snapshot();
        long events = first.eventCount();
        log.info(">>> 租约重放用例: 停机前 事件数={} 回收={}笔 冻结={}Wh 物理剩余={}Wh 活动={}",
                events, reaped.size(), snap1.frozenWh(), snap1.remainingWh(),
                snap1.activeReservations().stream().map(Reservation::requestId).toList());
        assertEquals(1, reaped.size());
        assertEquals(3_000, snap1.frozenWh(), 1e-9, "仅 E3 仍活动并冻结");
        assertEquals(TestSystems.CAPACITY_WH - 1_000, snap1.remainingWh(), 1e-9);

        // 模拟进程重启：全新设备模型 + 停在停机前时刻的时钟，重放同一日志
        MutableTimeSource clock2 = new MutableTimeSource(clock1.nowMs());
        PowerCoordinationService rebuilt = TestSystems.newService(clock2, new FileEventStore(logFile));
        var snap2 = rebuilt.snapshot();
        assertEquals(snap1.remainingWh(), snap2.remainingWh(), 1e-9);
        assertEquals(snap1.frozenWh(), snap2.frozenWh(), 1e-9);
        assertEquals(1, snap2.activeReservations().size());
        Reservation e3 = rebuilt.activeReservations().get(0);
        assertEquals("E3", e3.requestId());
        assertEquals(T0 + 10_000, e3.expiresAtEpochMs(), "续期后的到期时刻必须随日志复现");
        assertEquals(ReservationStatus.RESERVED, e3.status());
        assertEquals(events, rebuilt.eventCount());

        // 重放后再推进到 E3 到期，回收必须照常发生，且不重复回收 E1
        clock2.advance(100_000);
        var reaped2 = rebuilt.reapExpiredReservations();
        assertEquals(1, reaped2.size(), "仅 E3 应在重启后被回收（E1 已终结）");
        assertEquals("E3", reaped2.get(0).requestId());
        assertEquals(0, rebuilt.snapshot().frozenWh(), 1e-9);
        assertEquals(TestSystems.CAPACITY_WH - 1_000, rebuilt.snapshot().remainingWh(), 1e-9,
                "回收不动物理剩余");
        log.info("<<< 断言通过: 重启重放后剩余容量/有效租约/回收结果与停机前一致");
    }

    @Test
    void shutdownReleasesActiveLeasesAndExpiredOnesAreNotDoubleReleased() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        lease(svc, "F1", "s", 2_000, HOUR, 1_000);   // 到期先被回收
        lease(svc, "F2", "s", 3_000, HOUR, 100_000); // 停机时仍活动
        assertEquals(5_000, svc.snapshot().frozenWh(), 1e-9);

        clock.advance(1_001);
        var reading = new com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading(
                clock.nowMs(), 360, 400, 30, 35, 40, 400, true); // 过流 -> 停机
        var t = svc.reportFault(com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType.OVER_CURRENT,
                reading);
        log.info(">>> 停机联动用例: F1 已到期，过流停机迁移={}->{} 冻结={}Wh 活动={}",
                t.oldState(), t.newState(), svc.snapshot().frozenWh(),
                svc.activeReservations().size());
        assertEquals(FaultState.SHUTDOWN, svc.faultState());
        assertEquals(0, svc.snapshot().frozenWh(), 1e-9,
                "停机只释放活动预留 F2；F1 已由回收释放，不得重复释放");
        assertEquals(0, svc.activeReservations().size());
        // 终止原因可区分：F1 已被过期回收，F2 由停机释放
        assertEquals(PowerLedger.ReleaseOutcome.ALREADY_EXPIRED, svc.release("F1", "s"));
        assertEquals(PowerLedger.ReleaseOutcome.ALREADY_RELEASED, svc.release("F2", "s"));
        // 停机后带租约的新请求仍被拒绝
        PowerDecision d = lease(svc, "F3", "s", 1_000, HOUR, 1_000);
        assertEquals(RequestDecision.REJECTED, d.decision());
        log.info("<<< 断言通过: 停机释放活动租约、已回收租约无双重释放，新请求被拒");
    }

    @Test
    void concurrentRenewalsOnlyExtendLeaseAndNeverRefreeze() throws Exception {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        // 占 20000Wh 大额预留，24 线程疯狂续期；租约取超长（本用例不涉及到期，避免墙钟干扰）
        long longLease = 365L * 24L * HOUR;
        PowerDecision d = lease(svc, "C1", "s", 20_000, HOUR, longLease);
        assertEquals(RequestDecision.ACCEPTED, d.decision(), d.message());
        assertEquals(20_000, svc.snapshot().frozenWh(), 1e-9);
        double availableBefore = svc.snapshot().availableWh();
        long originalExpiry = T0 + longLease;

        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        java.util.List<PowerLedger.RenewResult> results =
                java.util.Collections.synchronizedList(new ArrayList<>());
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                awaitGate(gate);
                for (int k = 0; k < 20; k++) {
                    results.add(svc.renew("C1", "s", 100));
                }
            });
        }
        gate.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        long renewed = results.stream().filter(r -> r.outcome() == PowerLedger.RenewOutcome.RENEWED).count();
        log.info(">>> 并发续期用例: {} 次续期调用，成功={} 最终到期={} 冻结={}Wh 活动={}",
                results.size(), renewed,
                svc.activeReservations().get(0).expiresAtEpochMs(),
                svc.snapshot().frozenWh(), svc.activeReservations().size());

        assertEquals(threads * 20L, renewed, "到期前所有续期都应成功");
        assertEquals(1, svc.activeReservations().size());
        Reservation c1 = svc.activeReservations().get(0);
        assertEquals(20_000, c1.reservedWh(), 1e-9, "续期绝不重复冻结能量");
        assertEquals(20_000, c1.grantedPowerW(), 1e-9, "续期绝不改变授予功率");
        assertEquals(20_000, svc.snapshot().frozenWh(), 1e-9);
        // 每次 +100ms：逻辑时钟停在 T0，从原到期点连续累加 480 次
        assertEquals(originalExpiry + threads * 20L * 100L, c1.expiresAtEpochMs());
        assertEquals(availableBefore, svc.snapshot().availableWh(), 1e-6,
                "续期前后可调度容量必须不变（不释放、不新增）");
        log.info("<<< 断言通过: {} 次并发续期仅延长有效期，冻结/授予恒定", renewed);
    }

    @Test
    void renewDoesNotGrantAdditionalCapacity() {
        MutableTimeSource clock = new MutableTimeSource(T0);
        PowerCoordinationService svc = ready(clock);
        // 容量被两笔各 20000Wh 占满；给其中一笔超长续期后，新请求仍必须被拒绝
        lease(svc, "U1", "s", 20_000, HOUR, 1_000);
        PowerDecision u2 = lease(svc, "U2", "s", 20_000, HOUR, 1_000);
        assertEquals(RequestDecision.ACCEPTED, u2.decision());
        for (int k = 0; k < 10; k++) {
            assertEquals(PowerLedger.RenewOutcome.RENEWED, svc.renew("U1", "s", 100_000).outcome());
        }
        PowerDecision extra = lease(svc, "U3", "s", 1_000, HOUR, 1_000);
        log.info(">>> 续期不增容用例: 10 次续期后新请求 -> {} 授予={}W 冻结={}Wh",
                extra.decision(), extra.grantedPowerW(), svc.snapshot().frozenWh());
        assertEquals(RequestDecision.REJECTED, extra.decision(), "续期不得释放或新增容量");
        assertEquals(0, extra.grantedPowerW(), 1e-9);
        assertEquals(40_000, svc.snapshot().frozenWh(), 1e-9);
        log.info("<<< 断言通过: 续期不带来任何额外容量");
    }

    @Test
    void backgroundReaperReclaimsLeasesAutomaticallyWithoutSleep() throws Exception {
        MutableTimeSource clock = new MutableTimeSource(T0);
        // 50ms 周期的后台回收器；时钟由测试手动推进，无需真实等待到期
        PowerCoordinationService svc = new PowerCoordinationService(
                TestSystems.battery(), TestSystems.inverter(), TestSystems.motor(),
                new InMemoryEventStore(), clock, 0.5, TestSystems.SHUTDOWN_RESERVE_WH, 50);
        try {
            svc.ingestReading(TestSystems.healthy());
            lease(svc, "B1", "s", 7_000, HOUR, 1_000);
            assertEquals(7_000, svc.snapshot().frozenWh(), 1e-9);
            clock.advance(1_001); // 逻辑时间到期；等真实一个回收周期
            long deadline = System.currentTimeMillis() + 3_000;
            while (svc.snapshot().frozenWh() > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            log.info(">>> 后台回收用例: 逻辑到期后后台线程自动回收，冻结={}Wh 活动={}",
                    svc.snapshot().frozenWh(), svc.activeReservations().size());
            assertEquals(0, svc.snapshot().frozenWh(), 1e-9, "后台回收器必须自动回收到期租约");
            assertEquals(0, svc.activeReservations().size());
            assertEquals(TestSystems.CAPACITY_WH, svc.snapshot().availableWh(), 1e-9);
            log.info("<<< 断言通过: 无需人工介入，后台租约回收器完成回收");
        } finally {
            svc.shutdown();
        }
    }
}
