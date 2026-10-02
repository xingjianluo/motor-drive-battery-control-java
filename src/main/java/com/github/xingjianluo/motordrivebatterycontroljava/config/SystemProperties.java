package com.github.xingjianluo.motordrivebatterycontroljava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 系统可配置参数（application.properties 前缀 power.control）。 */
@ConfigurationProperties(prefix = "power.control")
public class SystemProperties {

    /** 事件日志文件路径；为空表示仅使用内存日志（不落盘、不可跨进程重放）。 */
    private String eventLogPath = "";
    /** 过温降级后允许的功率系数（0~1）。 */
    private double overTempDerateFactor = 0.5;
    /** 停机闭锁时冻结的安全储备（Wh）。 */
    private double shutdownReserveWh = 200;
    /** 租约到期自动回收的扫描间隔（毫秒）；到期回收同时在各入口惰性触发，定时器只是兜底。 */
    private long leaseSweepIntervalMs = 1_000;

    public String getEventLogPath() { return eventLogPath; }
    public void setEventLogPath(String eventLogPath) { this.eventLogPath = eventLogPath; }
    public double getOverTempDerateFactor() { return overTempDerateFactor; }
    public void setOverTempDerateFactor(double v) { this.overTempDerateFactor = v; }
    public double getShutdownReserveWh() { return shutdownReserveWh; }
    public void setShutdownReserveWh(double v) { this.shutdownReserveWh = v; }
    public long getLeaseSweepIntervalMs() { return leaseSweepIntervalMs; }
    public void setLeaseSweepIntervalMs(long v) { this.leaseSweepIntervalMs = v; }
}
