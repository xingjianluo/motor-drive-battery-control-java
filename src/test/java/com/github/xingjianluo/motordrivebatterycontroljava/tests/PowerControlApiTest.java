package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 端到端冒烟：传感 -> 功率请求 -> 撤销/结算 -> 状态查询，
 * 覆盖并发入口背后的同一协调器串行化路径。
 *
 * <p>用例隔离：每个用例执行后 {@link DirtiesContext} 强制关闭并重建整个 Spring
 * ApplicationContext（电池/逆变器/电机模型、协调器、内存事件日志、后台回收线程全部
 * 重新创建），因此任何用例都不会读到上一个用例的账本/物理剩余/故障状态，
 * 用例先后顺序（含随机顺序、单独执行、重复执行）不影响结果。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ExtendWith(com.github.xingjianluo.motordrivebatterycontroljava.support.TestCaseResultLogger.class)
class PowerControlApiTest {

    private static final Logger log = LoggerFactory.getLogger(PowerControlApiTest.class);

    /** 出厂物理容量（与 config/SystemBeans 一致），用作“干净起点”前置断言。 */
    private static final double FACTORY_CAPACITY_WH = 40_000.0;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    /** 用例入口前置断言：系统必须是全新出厂状态，证明没有读到别人留下的状态。 */
    @SuppressWarnings("unchecked")
    private void assertPristineSystem() {
        Map<String, Object> status = rest.getForObject(url("/status"), Map.class);
        assertEquals("RUNNING", status.get("faultState"), "前置：故障态必须是出厂 RUNNING");
        assertEquals(FACTORY_CAPACITY_WH, ((Number) status.get("remainingWh")).doubleValue(), 1e-9,
                "前置：物理剩余必须是出厂满电量，否则读到了上一个用例留下的状态");
        assertEquals(0.0, ((Number) status.get("frozenWh")).doubleValue(), 1e-9,
                "前置：冻结必须为零");
        assertEquals(FACTORY_CAPACITY_WH, ((Number) status.get("availableWh")).doubleValue(), 1e-9,
                "前置：可调度必须是出厂满电量");
        assertEquals(0, ((List<?>) status.get("activeReservations")).size(),
                "前置：不得存在活动预留");
        assertEquals(0, ((Number) status.get("ledgerVersion")).longValue(),
                "前置：账本版本必须为零（事件日志必须是空的）");
        log.info("[前置] 全新系统确认: remaining={}Wh frozen={}Wh ledgerVersion={}",
                status.get("remainingWh"), status.get("frozenWh"), status.get("ledgerVersion"));
    }

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
        assertPristineSystem();
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
        log.info("[结果] 完整流程结束: remaining={}Wh frozen={}Wh available={}Wh",
                status.get("remainingWh"), status.get("frozenWh"), status.get("availableWh"));
    }

    @Test
    void leaseRequestAndRenewalWorkOverHttp() {
        assertPristineSystem();
        // 健康读数（@DirtiesContext 保证本用例拿到全新上下文；这里再显式前置断言）
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

        @SuppressWarnings("unchecked")
        Map<String, Object> status = rest.getForObject(url("/status"), Map.class);
        log.info("[结果] 租约流程结束: outcomeAfterSettle={} remaining={}Wh frozen={}Wh active={}",
                renewAfter.get("outcome"), status.get("remainingWh"), status.get("frozenWh"),
                ((List<?>) status.get("activeReservations")).size());
    }
}
