package com.github.xingjianluo.motordrivebatterycontroljava.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultManager;
import com.github.xingjianluo.motordrivebatterycontroljava.fault.FaultType;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerDecision;
import com.github.xingjianluo.motordrivebatterycontroljava.model.PowerRequest;
import com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.service.SystemSnapshot;

/**
 * 并发入口的 REST 外观。所有写操作最终都在协调器同一把锁内串行化。
 */
@RestController
@RequestMapping("/api/power")
public class PowerControlController {

    public record SensorReadingDto(Double batteryVoltageV, Double batteryCurrentA,
                                   Double batteryTempC, Double inverterTempC,
                                   Double motorTempC, Double motorCurrentA,
                                   Boolean valid) { }

    public record SettleRequest(String sourceId, double actualConsumedWh) { }

    public record RenewRequest(String sourceId, long extendByMs) { }

    public record FaultClearRequest(SensorReadingDto reading) { }

    private final PowerCoordinationService coordinator;

    public PowerControlController(PowerCoordinationService coordinator) {
        this.coordinator = coordinator;
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.OK)
    public PowerDecision request(@RequestBody PowerRequest request) {
        return coordinator.request(request);
    }

    @PostMapping("/requests/{requestId}/release")
    public Map<String, Object> release(@PathVariable String requestId,
                                       @RequestParam String sourceId) {
        var outcome = coordinator.release(requestId, sourceId);
        return Map.of("requestId", requestId, "outcome", outcome.name());
    }

    @PostMapping("/requests/{requestId}/settle")
    public Map<String, Object> settle(@PathVariable String requestId,
                                      @RequestBody SettleRequest body) {
        var result = coordinator.settle(requestId, body.sourceId(), body.actualConsumedWh());
        return Map.of("requestId", requestId,
                "outcome", result.outcome().name(),
                "refundedWh", result.refundedWh());
    }

    @PostMapping("/requests/{requestId}/renew")
    public Map<String, Object> renew(@PathVariable String requestId,
                                     @RequestBody RenewRequest body) {
        var result = coordinator.renew(requestId, body.sourceId(), body.extendByMs());
        return Map.of("requestId", requestId,
                "outcome", result.outcome().name(),
                "newExpiresAtMs", result.newExpiresAtMs());
    }

    @PostMapping("/sensors")
    public Map<String, Object> sensors(@RequestBody SensorReadingDto dto) {
        SensorReading reading = toReading(dto);
        var detected = coordinator.ingestReading(reading);
        return Map.of("newlyLatchedFaults", detected,
                "faultState", coordinator.faultState().name());
    }

    @PostMapping("/faults/{type}")
    public FaultManager.Transition reportFault(@PathVariable String type,
                                               @RequestBody(required = false) SensorReadingDto dto) {
        FaultType faultType = FaultType.valueOf(type);
        return coordinator.reportFault(faultType, dto == null ? null : toReading(dto));
    }

    @DeleteMapping("/faults/{type}")
    public FaultManager.Transition clearFault(@PathVariable String type,
                                              @RequestBody(required = false) SensorReadingDto dto) {
        FaultType faultType = FaultType.valueOf(type);
        // 迟滞条件由状态机基于本次上报读数判定；不传读数则以设备最新遥测判定
        return coordinator.clearFault(faultType, dto == null ? null : toReading(dto));
    }

    @GetMapping("/status")
    public SystemSnapshot status() {
        return coordinator.snapshot();
    }

    private static SensorReading toReading(SensorReadingDto d) {
        boolean valid = !Boolean.FALSE.equals(d.valid());
        if (!valid) {
            return SensorReading.invalid(System.currentTimeMillis());
        }
        return new SensorReading(System.currentTimeMillis(),
                nz(d.batteryVoltageV()), nz(d.batteryCurrentA()),
                nz(d.batteryTempC()), nz(d.inverterTempC()),
                nz(d.motorTempC()), nz(d.motorCurrentA()), true);
    }

    private static double nz(Double d) {
        return d == null ? 0.0 : d;
    }
}
