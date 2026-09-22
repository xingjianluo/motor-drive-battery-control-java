package com.github.xingjianluo.motordrivebatterycontroljava.power.api;

import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.FaultReport;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.SystemSnapshot;
import com.github.xingjianluo.motordrivebatterycontroljava.power.service.PowerCoordinator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 并发控制入口之一：REST。所有写操作经 PowerCoordinator 同一把锁串行化。
 */
@RestController
@RequestMapping("/api/power")
public class PowerControlController {

    private final PowerCoordinator coordinator;

    public PowerControlController(PowerCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** 请求功率 */
    @PostMapping("/requests")
    public PowerDecision request(@RequestBody PowerRequest request) {
        return coordinator.request(request);
    }

    /** 撤销预留 */
    @PostMapping("/requests/{requestId}/release")
    public Map<String, Object> release(@PathVariable String requestId) {
        Optional<Double> released = coordinator.release(requestId);
        return Map.of("requestId", requestId,
                "released", released.isPresent(),
                "releasedWatts", released.orElse(0.0));
    }

    /** 结算预留 */
    @PostMapping("/requests/{requestId}/settle")
    public Map<String, Object> settle(@PathVariable String requestId,
                                      @RequestBody Map<String, Object> body) {
        double energyWh = ((Number) body.getOrDefault("actualEnergyWh", 0.0)).doubleValue();
        Optional<Double> settled = coordinator.settle(requestId, energyWh);
        return Map.of("requestId", requestId,
                "settled", settled.isPresent(),
                "settledWatts", settled.orElse(0.0));
    }

    /** 故障上报 */
    @PostMapping("/faults")
    public FaultDecision reportFault(@RequestBody FaultReport report) {
        return coordinator.reportFault(report);
    }

    /** 故障恢复上报 */
    @PostMapping("/faults/{faultKey}/clear")
    public Map<String, Object> clearFault(@PathVariable String faultKey,
                                          @RequestBody Map<String, Object> body) {
        double value = ((Number) body.getOrDefault("measuredValue", 0.0)).doubleValue();
        return Map.of("faultKey", faultKey, "cleared", coordinator.clearFault(faultKey, value));
    }

    /** 一致快照 */
    @GetMapping("/snapshot")
    public SystemSnapshot snapshot() {
        return coordinator.snapshot();
    }
}
