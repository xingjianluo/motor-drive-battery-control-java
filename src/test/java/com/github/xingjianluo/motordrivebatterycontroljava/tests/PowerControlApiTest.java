package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HTTP 端到端冒烟：传感 -> 功率请求 -> 撤销/结算 -> 状态查询，
 * 覆盖并发入口背后的同一协调器串行化路径。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PowerControlApiTest {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

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
    @DirtiesContext // 本用例实耗能量，结束后重建上下文，避免影响其它用例的电量基线
    void leaseAndRenewOverHttp() {
        Map<String, Object> sensor = Map.of(
                "batteryVoltageV", 360, "batteryCurrentA", 10,
                "batteryTempC", 30, "inverterTempC", 35,
                "motorTempC", 40, "motorCurrentA", 20, "valid", true);
        rest.postForEntity(url("/sensors"), new HttpEntity<>(sensor, json()), Map.class);

        // 带租约的受理
        Map<String, Object> req = Map.of(
                "requestId", "http-lease-1", "sourceId", "pedal",
                "requestedPowerW", 10_000, "durationMs", 3_600_000,
                "leaseTtlMs", 600_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> decision = rest.postForObject(url("/requests"),
                new HttpEntity<>(req, json()), Map.class);
        assertEquals("ACCEPTED", decision.get("decision"));

        // 有效期内续期成功，返回新到期时刻
        Map<String, Object> renewBody = Map.of("sourceId", "pedal", "extendByMs", 600_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> renewed = rest.postForObject(
                url("/requests/http-lease-1/renew"),
                new HttpEntity<>(renewBody, json()), Map.class);
        assertEquals("RENEWED", renewed.get("outcome"));
        assertNotNull(renewed.get("newExpiresAtMs"));

        // 结算后再续期：返回可区分结果，不重新激活
        Map<String, Object> settle = Map.of("sourceId", "pedal", "actualConsumedWh", 10_000);
        rest.postForObject(url("/requests/http-lease-1/settle"),
                new HttpEntity<>(settle, json()), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> renewAfterSettle = rest.postForObject(
                url("/requests/http-lease-1/renew"),
                new HttpEntity<>(renewBody, json()), Map.class);
        assertEquals("ALREADY_SETTLED", renewAfterSettle.get("outcome"));

        // 不存在的预留续期
        @SuppressWarnings("unchecked")
        Map<String, Object> renewGhost = rest.postForObject(
                url("/requests/http-ghost/renew"),
                new HttpEntity<>(renewBody, json()), Map.class);
        assertEquals("NOT_FOUND", renewGhost.get("outcome"));
    }
}
