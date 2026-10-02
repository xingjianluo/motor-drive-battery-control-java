package com.github.xingjianluo.motordrivebatterycontroljava.config;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.xingjianluo.motordrivebatterycontroljava.ledger.Reservation;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;

import jakarta.annotation.PreDestroy;

/**
 * 租约到期回收的定时兜底（守护线程）：周期触发协调器的 sweepExpired。
 * 到期回收在各入口已惰性生效，本定时器只保证"无任何入口活动时"也能及时释放冻结容量。
 */
public class LeaseExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaseExpiryScheduler.class);

    private final PowerCoordinationService coordinator;
    private final long intervalMs;
    private final ScheduledExecutorService executor;

    public LeaseExpiryScheduler(PowerCoordinationService coordinator, long intervalMs) {
        this.coordinator = coordinator;
        this.intervalMs = Math.max(100, intervalMs);
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lease-expiry-sweeper");
            t.setDaemon(true);
            return t;
        });
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        executor.scheduleWithFixedDelay(this::sweepSafely,
                intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        log.info("[租约] 到期回收定时器已启动，间隔={}ms", intervalMs);
    }

    private void sweepSafely() {
        try {
            List<Reservation> expired = coordinator.sweepExpired();
            if (!expired.isEmpty()) {
                log.info("[租约] 定时回收 {} 笔到期预留", expired.size());
            }
        } catch (RuntimeException e) {
            log.warn("[租约] 定时回收异常（下周期重试）: {}", e.toString());
        }
    }

    @PreDestroy
    public void stop() {
        executor.shutdownNow();
    }
}
