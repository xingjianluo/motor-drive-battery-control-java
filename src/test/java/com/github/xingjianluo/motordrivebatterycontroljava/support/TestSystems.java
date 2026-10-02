package com.github.xingjianluo.motordrivebatterycontroljava.support;

import com.github.xingjianluo.motordrivebatterycontroljava.device.Battery;
import com.github.xingjianluo.motordrivebatterycontroljava.device.BatterySpec;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Inverter;
import com.github.xingjianluo.motordrivebatterycontroljava.device.InverterSpec;
import com.github.xingjianluo.motordrivebatterycontroljava.device.Motor;
import com.github.xingjianluo.motordrivebatterycontroljava.device.MotorSpec;
import com.github.xingjianluo.motordrivebatterycontroljava.event.InMemoryEventStore;
import com.github.xingjianluo.motordrivebatterycontroljava.service.PowerCoordinationService;
import com.github.xingjianluo.motordrivebatterycontroljava.time.MutableTimeSource;
import com.github.xingjianluo.motordrivebatterycontroljava.time.TimeSource;

/** 测试夹具：与生产 Bean 同规格的进程内系统。 */
public final class TestSystems {

    public static final double CAPACITY_WH = 40_000;
    public static final double NOMINAL_V = 350;
    public static final double MAX_CURRENT_A = 300;
    public static final double UV_V = 300;
    public static final double INVERTER_LIMIT_W = 80_000;
    public static final double MIN_POWER_W = 1_000;
    public static final double SHUTDOWN_RESERVE_WH = 200;

    private TestSystems() { }

    public static Battery battery() {
        return new Battery(new BatterySpec(CAPACITY_WH, NOMINAL_V, MAX_CURRENT_A,
                UV_V, 55, 45));
    }

    public static Inverter inverter() {
        return new Inverter(new InverterSpec(INVERTER_LIMIT_W, 65, 55));
    }

    public static Motor motor() {
        return new Motor(new MotorSpec(250, 70, MIN_POWER_W));
    }

    public static PowerCoordinationService newService() {
        Battery b = battery();
        Inverter i = inverter();
        Motor m = motor();
        return new PowerCoordinationService(b, i, m, new InMemoryEventStore(),
                0.5, SHUTDOWN_RESERVE_WH);
    }

    /** 可控逻辑时钟的系统（不启动后台回收器，到期由业务入口或显式调用触发）。 */
    public static PowerCoordinationService newService(TimeSource clock) {
        return new PowerCoordinationService(battery(), inverter(), motor(),
                new InMemoryEventStore(), clock, 0.5, SHUTDOWN_RESERVE_WH, 0);
    }

    /** 可控逻辑时钟 + 指定事件日志（用于重放复现验证）。 */
    public static PowerCoordinationService newService(TimeSource clock,
                                                      com.github.xingjianluo.motordrivebatterycontroljava.event.EventStore store) {
        return new PowerCoordinationService(battery(), inverter(), motor(),
                store, clock, 0.5, SHUTDOWN_RESERVE_WH, 0);
    }

    /** 360V/10A/30℃ 的健康读数。 */
    public static com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading healthy() {
        return new com.github.xingjianluo.motordrivebatterycontroljava.model.SensorReading(
                System.currentTimeMillis(), 360, 10, 30, 35, 40, 20, true);
    }
}
