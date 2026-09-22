package com.github.xingjianluo.motordrivebatterycontroljava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST 并发入口端到端验证：超额裁剪、停机拒绝、撤销幂等。
 */
@SpringBootTest
@AutoConfigureMockMvc
class PowerControlApiTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void requestClipFaultRejectReleaseFlow() throws Exception {
        long now = System.nanoTime();

        // 1) 超额请求 120kW -> 裁剪到 80kW，bindingLimit=INVERTER_POWER
        mvc.perform(post("/api/power/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-1\",\"source\":\"rest\","
                                + "\"requestedWatts\":120000.0,\"requestedAtNanos\":" + now + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.clipped").value(true))
                .andExpect(jsonPath("$.grantedWatts").value(80000.0))
                .andExpect(jsonPath("$.bindingLimit").value("INVERTER_POWER"));

        // 2) 预算已冻结占满 -> 第二笔被 BATTERY_BUDGET_EXHAUSTED 拒绝
        mvc.perform(post("/api/power/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-2\",\"source\":\"rest\","
                                + "\"requestedWatts\":1000.0,\"requestedAtNanos\":" + now + "}"))
                .andExpect(jsonPath("$.accepted").value(false))
                .andExpect(jsonPath("$.rejection").value("BATTERY_BUDGET_EXHAUSTED"));

        // 3) 过流故障 -> 停机
        mvc.perform(post("/api/power/faults")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reportId\":\"f1\",\"faultKey\":\"oc-1\","
                                + "\"type\":\"OVER_CURRENT\",\"measuredValue\":320.0,\"reportedAtNanos\":" + now + "}"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.resultingState").value("STOPPED"));

        // 4) 停机后请求 -> SYSTEM_STOPPED
        mvc.perform(post("/api/power/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"api-3\",\"source\":\"rest\","
                                + "\"requestedWatts\":1000.0,\"requestedAtNanos\":" + now + "}"))
                .andExpect(jsonPath("$.rejection").value("SYSTEM_STOPPED"));

        // 5) 撤销 api-1 成功；重复撤销 released=false（不重复释放）
        mvc.perform(post("/api/power/requests/api-1/release"))
                .andExpect(jsonPath("$.released").value(true))
                .andExpect(jsonPath("$.releasedWatts").value(80000.0));
        mvc.perform(post("/api/power/requests/api-1/release"))
                .andExpect(jsonPath("$.released").value(false))
                .andExpect(jsonPath("$.releasedWatts").value(0.0));

        // 6) 快照一致
        mvc.perform(get("/api/power/snapshot"))
                .andExpect(jsonPath("$.state").value("STOPPED"))
                .andExpect(jsonPath("$.ledger.activeReservations").value(0))
                .andExpect(jsonPath("$.ledger.reservedWatts").value(0.0));
    }
}
