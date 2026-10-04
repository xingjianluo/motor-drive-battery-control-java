package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import com.github.xingjianluo.motordrivebatterycontroljava.config.SystemProperties;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Battery;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Inverter;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Motor;
import com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.time.MutableTimeSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 端到端冒烟：传感 -> 功率请求 -> 撤销/结算 -> 状态查询，
 * 覆盖并发入口背后的同一协调器串行化路径。
 *
 * <p>用例隔离：协调器/电池/事件日志/后台租约回收线程都是 Spring 单例且带状态，
 * 因此每个用例结束后销毁并重建应用上下文（新电池满电、空事件日志、新协调器、
 * 旧 reaper 随 @PreDestroy 停止）。这样无论单独跑、打乱顺序还是重复跑，
 * 每个用例看到的都是初始 40000Wh 的干净系统，互不串味。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PowerControlApiTest {

    /** 逻辑时钟固定起点（毫秒），使带租约/到期类 HTTP 用例无需真实等待即可确定复现。 */
    private static final long T0 = 1_000_000L;

    /**
     * 用可控逻辑时钟替换生产的墙钟协调器（不启动后台回收线程，到期由业务入口触发），
     * 这样 HTTP 端到端的到期回收用例只靠手动拨钟，不靠 sleep 碰运气。
     */
    @TestConfiguration
    static class ControlledClockConfig {
        @Bean
        @Primary
        MutableTimeSource testClock() {
            return new MutableTimeSource(T0);
        }

        @Bean
        @Primary
        PowerCoordinationService testCoordinator(Battery battery, Inverter inverter,
                                                 Motor motor, EventStore eventStore,
                                                 MutableTimeSource testClock,
                                                 SystemProperties props) {
            return new PowerCoordinationService(battery, inverter, motor, eventStore,
                    testClock, props.getOverTempDerateFactor(),
                    props.getShutdownReserveWh(), 0);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MutableTimeSource clock;

    private String url(String path) {
        return "http://localhost:" + port + "/api/power" + path;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    void fullFlowOverHttp() {
        // 健康读数
        Map<String, Object> sensor = Map.of(
                "batteryVoltageV", 360, "batteryCurrentA", 10,
                "batteryTempC", 30, "inverterTempC", 35,
                "motorTempC", 40, "motorCurrentA", 20, "valid", true);
        var ingestResp = rest.postForEntity(url("/sensors"),
                new HttpEntity<>(sensor, json()), Map.class);
        assertEquals(200, ingestResp.getStatusCode().value());

        // 正常请求
        Map<String, Object> req = Map.of(
                "requestId", "http-1", "sourceId", "pedal",
                "requestedPowerW", 10_000, "durationMs", 3_600_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = rest.postForObject(url("/requests"),
                new HttpEntity<>(req, json()), Map.class);
        assertEquals("ACCEPTED", decision.get("decision"));

        // 超过逆变器硬上限
        Map<String, Object> over = Map.of(
                "requestId", "http-2", "sourceId", "pedal",
                "requestedPowerW", 90_000, "durationMs", 1_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> rejected = rest.postForObject(url("/requests"),
                new HttpEntity<>(over, json()), Map.class);
        assertEquals("REJECTED", rejected.get("decision"));
        assertEquals("INVERTER_HARDWARE_LIMIT",
                ((java.util.List<?>) rejected.get("reasons")).get(0));

        // 结算
        Map<String, Object> settle = Map.of("sourceId", "pedal", "actualConsumedWh", 8_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> settled = rest.postForObject(
                url("/requests/http-1/settle"),
                new HttpEntity<>(settle, json()), Map.class);
        assertEquals("SETTLED", settled.get("outcome"));

        // 状态查询
        @SuppressWarnings("unchecked")
        Map<String, Object> status = rest.getForObject(url("/status"), Map.class);
        assertNotNull(status.get("faultState"));
        assertEquals(32_000.0, ((Number) status.get("remainingWh")).doubleValue(), 1e-6);
    }

    @Test
    void leaseRequestAndRenewalWorkOverHttp() {
        // 健康读数（与 fullFlowOverHttp 相互独立，使用新的 requestId）
        Map<String, Object> sensor = Map.of(
                "batteryVoltageV", 360, "batteryCurrentA", 10,
                "batteryTempC", 30, "inverterTempC", 35,
                "motorTempC", 40, "motorCurrentA", 20, "valid", true);
        rest.postForEntity(url("/sensors"), new HttpEntity<>(sensor, json()), Map.class);

        // 带有效期的请求
        Map<String, Object> req = Map.of(
                "requestId", "lease-1", "sourceId", "pedal",
                "requestedPowerW", 6_000, "durationMs", 3_600_000, "leaseMs", 60_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = rest.postForObject(url("/requests"),
                new HttpEntity<>(req, json()), Map.class);
        assertEquals("ACCEPTED", decision.get("decision"));

        // 续期：只延长，成功并返回新的到期时刻
        Map<String, Object> renew = Map.of("sourceId", "pedal", "extendMs", 30_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> renewed = rest.postForObject(
                url("/requests/lease-1/renew"), new HttpEntity<>(renew, json()), Map.class);
        assertEquals("RENEWED", renewed.get("outcome"));
        assertTrue(((Number) renewed.get("expiresAtEpochMs")).longValue() > 0);

        // 结算后再续期：必须被明确拒绝，不能重新激活
        Map<String, Object> settle = Map.of("sourceId", "pedal", "actualConsumedWh", 2_000);
        rest.postForObject(url("/requests/lease-1/settle"),
                new HttpEntity<>(settle, json()), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> renewAfter = rest.postForObject(
                url("/requests/lease-1/renew"), new HttpEntity<>(renew, json()), Map.class);
        assertEquals("ALREADY_SETTLED", renewAfter.get("outcome"));
    }

    @Test
    void leaseExpiryReclaimsFrozenCapacityOverHttpWithoutRealWaiting() {
        // 健康读数
        Map<String, Object> sensor = Map.of(
                "batteryVoltageV", 360, "batteryCurrentA", 10,
                "batteryTempC", 30, "inverterTempC", 35,
                "motorTempC", 40, "motorCurrentA", 20, "valid", true);
        rest.postForEntity(url("/sensors"), new HttpEntity<>(sensor, json()), Map.class);

        // 带 1000ms 有效期的请求：6000W*1h = 6000Wh 冻结
        Map<String, Object> req = Map.of(
                "requestId", "exp-1", "sourceId", "pedal",
                "requestedPowerW", 6_000, "durationMs", 3_600_000, "leaseMs", 1_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = rest.postForObject(url("/requests"),
                new HttpEntity<>(req, json()), Map.class);
        assertEquals("ACCEPTED", decision.get("decision"));
        assertStatus(6_000.0, 34_000.0, 40_000.0, "受理后冻结 6000Wh、可调度 34000Wh、物理剩余不动");

        // 到期前一刻：冻结仍在
        clock.advance(999);
        assertStatus(6_000.0, 34_000.0, 40_000.0, "到期前一刻不得回收");

        // 逻辑时钟越过到期点（无真实等待）；下一次业务入口（查状态）必须触发自动回收
        clock.advance(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> afterExpiry = rest.getForObject(url("/status"), Map.class);
        assertEquals(0.0, num(afterExpiry, "frozenWh"), 1e-6, "到期后冻结必须自动清零");
        assertEquals(40_000.0, num(afterExpiry, "availableWh"), 1e-6, "容量必须归还可调度池");
        assertEquals(40_000.0, num(afterExpiry, "remainingWh"), 1e-6, "回收不动物理剩余");
        assertEquals(0, ((List<?>) afterExpiry.get("activeReservations")).size(),
                "到期预留不应再出现在活动列表");

        // 回收后结算/撤销必须被明确告知已过期，不能重复动账
        Map<String, Object> settle = Map.of("sourceId", "pedal", "actualConsumedWh", 100);
        @SuppressWarnings("unchecked")
        Map<String, Object> settled = rest.postForObject(
                url("/requests/exp-1/settle"), new HttpEntity<>(settle, json()), Map.class);
        assertEquals("ALREADY_EXPIRED", settled.get("outcome"), "回收后结算必须被拒绝");
        assertStatus(0.0, 40_000.0, 40_000.0, "回收后再结算不得扣减物理能量");

        // 归还的容量必须立刻能被新请求重新调度
        Map<String, Object> reuse = Map.of(
                "requestId", "exp-2", "sourceId", "pedal",
                "requestedPowerW", 10_000, "durationMs", 3_600_000, "leaseMs", 5_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> reused = rest.postForObject(url("/requests"),
                new HttpEntity<>(reuse, json()), Map.class);
        assertEquals("ACCEPTED", reused.get("decision"),
                "回收释放的容量必须可立即重新受理: " + reused.get("message"));
        assertStatus(10_000.0, 30_000.0, 40_000.0, "重新受理后冻结 10000Wh");
    }

    private void assertStatus(double frozenWh, double availableWh,
                              double remainingWh, String msg) {
        @SuppressWarnings("unchecked")
        Map<String, Object> s = rest.getForObject(url("/status"), Map.class);
        assertEquals(frozenWh, num(s, "frozenWh"), 1e-6, msg + "（frozenWh）");
        assertEquals(availableWh, num(s, "availableWh"), 1e-6, msg + "（availableWh）");
        assertEquals(remainingWh, num(s, "remainingWh"), 1e-6, msg + "（remainingWh）");
    }

    private static double num(Map<String, Object> s, String key) {
        return ((Number) s.get(key)).doubleValue();
    }
}
