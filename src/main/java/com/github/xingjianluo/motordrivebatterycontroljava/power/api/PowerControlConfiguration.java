package com.github.xingjianluo.motordrivebatterycontroljava.power.api;

import com.github.xingjianluo.motordrivebatterycontroljava.power.event.EventLog;
import com.github.xingjianluo.motordrivebatterycontroljava.power.event.FileEventLog;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.BatteryModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.InverterModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.model.MotorModel;
import com.github.xingjianluo.motordrivebatterycontroljava.power.service.PowerCoordinator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 组件装配：模型参数可在 application.properties 覆盖；启动时重放事件日志恢复状态。
 */
@Configuration
public class PowerControlConfiguration {

    @Bean
    public BatteryModel batteryModel(
            @Value("${power.battery.voltage:350.0}") double voltage,
            @Value("${power.battery.capacityAh:60.0}") double capacityAh,
            @Value("${power.battery.maxCurrentA:300.0}") double maxCurrentA,
            @Value("${power.battery.initialSoc:0.9}") double initialSoc) {
        return new BatteryModel(voltage, capacityAh, maxCurrentA, initialSoc);
    }

    @Bean
    public InverterModel inverterModel(@Value("${power.inverter.maxPowerW:80000.0}") double maxPowerW) {
        return new InverterModel(maxPowerW);
    }

    @Bean
    public MotorModel motorModel(@Value("${power.motor.maxRpm:12000.0}") double maxRpm,
                                 @Value("${power.motor.initialTempC:35.0}") double initialTempC,
                                 @Value("${power.motor.ratedPowerW:80000.0}") double ratedPowerW) {
        return new MotorModel(maxRpm, initialTempC, ratedPowerW);
    }

    @Bean
    public EventLog eventLog(@Value("${power.eventlog.path:./data/power-events.log}") String path) {
        return new FileEventLog(Path.of(path));
    }

    @Bean
    public PowerCoordinator powerCoordinator(BatteryModel battery,
                                             InverterModel inverter,
                                             MotorModel motor,
                                             EventLog eventLog) {
        PowerCoordinator coordinator = new PowerCoordinator(battery, inverter, motor, eventLog);
        coordinator.replay();
        return coordinator;
    }
}
