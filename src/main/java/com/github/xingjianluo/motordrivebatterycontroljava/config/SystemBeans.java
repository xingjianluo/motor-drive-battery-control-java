package com.github.xingjianluo.motordrivebatterycontroljava.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.xingjianluo.motordrivebatterycontroljava.device.Battery;
import com.github.xingjianluo.motordrivebatterycontroljava.device.BatterySpec;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Inverter;
import com.github.xingjianluo.motordrivebatterycontroljava.device.InverterSpec;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Motor;
import com.github.xingjianluo.motordrivebatterycontroljava.device.MotorSpec;
import com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.FileEventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.event.InMemoryEventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;

/** 默认物理模型与协调器装配（进程内单例）。 */
@Configuration
@EnableConfigurationProperties(SystemProperties.class)
public class SystemBeans {

    @Bean
    public EventStore eventStore(SystemProperties props) {
        if (props.getEventLogPath() != null && !props.getEventLogPath().isBlank()) {
            return new FileEventStore(Path.of(props.getEventLogPath()));
        }
        return new InMemoryEventStore();
    }

    @Bean
    public Battery battery() {
        // 40000 Wh 标称电量；350 V；300 A 最大放电；300 V 欠压；55/45 ℃ 过温迟滞
        return new Battery(new BatterySpec(40_000, 350, 300, 300, 55, 45));
    }

    @Bean
    public Inverter inverter() {
        // 80 kW 额定功率（硬上限）；65/55 ℃ 过温迟滞
        return new Inverter(new InverterSpec(80_000, 65, 55));
    }

    @Bean
    public Motor motor() {
        // 250 A 额定电流；70 ℃ 过温；1000 W 最小可执行功率
        return new Motor(new MotorSpec(250, 70, 1_000));
    }

    @Bean
    public PowerCoordinationService coordinator(Battery battery, Inverter inverter,
                                                Motor motor, EventStore eventStore,
                                                SystemProperties props) {
        return new PowerCoordinationService(battery, inverter, motor, eventStore,
                props.getOverTempDerateFactor(), props.getShutdownReserveWh());
    }

    /**
     * 租约到期自动回收的定时兜底：即使长时间没有新请求进入，
     * 到期预留也会被回收、冻结容量放回可调度池，无需人工介入。
     */
    @Bean
    public LeaseExpiryScheduler leaseExpiryScheduler(PowerCoordinationService coordinator,
                                                     SystemProperties props) {
        return new LeaseExpiryScheduler(coordinator, props.getLeaseSweepIntervalMs());
    }
}
