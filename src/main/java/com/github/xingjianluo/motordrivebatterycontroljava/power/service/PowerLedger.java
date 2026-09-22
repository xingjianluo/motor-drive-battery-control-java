package com.github.xingjianluo.motordrivebatterycontroljava.power.service;

import com.github.xingjianluo.motordrivebatterycontroljava.power.model.LedgerSnapshot;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.Reservation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 功率账本：进程内所有控制入口的预留、撤销、结算都按同一份账本生效。
 * <p>不变式：totalBudget = reserved + remaining；预留授予后其功率在结算/撤销前一直占用预算，
 * 因此总线上不会出现瞬时超额。本类自身不加锁，由 {@link PowerCoordinator} 的统一锁保证并发安全。
 * <p>幂等语义：
 * <ul>
 *   <li>重复 reserve 同一 requestId 返回 false，不二次占用；</li>
 *   <li>release / settle 同一 requestId 第二次返回 empty，不发生重复释放或重复扣减；</li>
 *   <li>因此不会出现预留泄漏（结算/撤销必然移除）或重复释放。</li>
 * </ul>
 */
public class PowerLedger {

    private static final Logger log = LoggerFactory.getLogger(PowerLedger.class);

    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private double totalBudgetWatts;
    private double reservedWatts;

    /** 以冻结的总预算初始化账本（启动时 / 重放时调用） */
    public void freezeBudget(double totalBudgetWatts) {
        if (totalBudgetWatts < 0) {
            throw new IllegalArgumentException("总预算不能为负");
        }
        this.totalBudgetWatts = totalBudgetWatts;
        log.info("[账本] 冻结总预算 totalBudget={}W，已预留={}W，剩余={}W",
                totalBudgetWatts, reservedWatts, remaining());
    }

    /**
     * 结算导致物理预算下降时调用：重新冻结总预算。
     * 若新预算小于已预留量，按已预留量兜底（不追溯裁剪既有预留，但此后剩余为 0，不再受理新预留）。
     */
    public void refreezeAfterSettle(double newPhysicalBudgetWatts) {
        double previous = totalBudgetWatts;
        totalBudgetWatts = Math.max(newPhysicalBudgetWatts, reservedWatts);
        log.info("[账本] 结算后重新冻结 totalBudget: {}W -> {}W（物理预算 {}W，已预留 {}W），剩余={}W",
                previous, totalBudgetWatts, newPhysicalBudgetWatts, reservedWatts, remaining());
    }

    /** 尝试预留，预算不足或 requestId 重复时返回 false */
    public boolean reserve(Reservation reservation) {
        double want = reservation.watts();
        if (want <= 0) {
            log.warn("[账本] 拒绝非正功率预留 requestId={} watts={}W", reservation.requestId(), want);
            return false;
        }
        if (reservations.containsKey(reservation.requestId())) {
            log.warn("[账本] 拒绝重复 requestId 的预留 requestId={}（已存在，不二次占用）", reservation.requestId());
            return false;
        }
        if (want > remaining() + 1e-6) {
            log.warn("[账本] 预算不足，拒绝预留 requestId={} need={}W remaining={}W",
                    reservation.requestId(), want, remaining());
            return false;
        }
        reservations.put(reservation.requestId(), reservation);
        reservedWatts += want;
        log.info("[账本] 预留生效 requestId={} source={} watts={}W -> 已预留={}W 剩余={}W",
                reservation.requestId(), reservation.source(), want, reservedWatts, remaining());
        return true;
    }

    /** 撤销预留（幂等），返回被释放的记录；重复撤销返回 empty */
    public Optional<Reservation> release(String requestId) {
        Reservation removed = reservations.remove(requestId);
        if (removed == null) {
            log.warn("[账本] 撤销未命中（可能已撤销/结算，或从未存在）requestId={}，不产生重复释放", requestId);
            return Optional.empty();
        }
        reservedWatts -= removed.watts();
        log.info("[账本] 撤销释放 requestId={} watts={}W -> 已预留={}W 剩余={}W",
                requestId, removed.watts(), reservedWatts, remaining());
        return Optional.of(removed);
    }

    /** 结算预留（幂等），返回被结算的记录；账本移除占用，能量扣减由协调器对电池执行后重新冻结 */
    public Optional<Reservation> settle(String requestId) {
        Reservation removed = reservations.remove(requestId);
        if (removed == null) {
            log.warn("[账本] 结算未命中（可能已撤销/结算）requestId={}，不产生重复扣减", requestId);
            return Optional.empty();
        }
        reservedWatts -= removed.watts();
        log.info("[账本] 结算移除占用 requestId={} watts={}W -> 已预留={}W 剩余={}W",
                requestId, removed.watts(), reservedWatts, remaining());
        return Optional.of(removed);
    }

    private double remaining() {
        return totalBudgetWatts - reservedWatts;
    }

    public LedgerSnapshot snapshot() {
        return new LedgerSnapshot(totalBudgetWatts, reservedWatts, remaining(), reservations.size());
    }
}
