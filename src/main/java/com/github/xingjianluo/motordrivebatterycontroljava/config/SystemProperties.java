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
    /**
     * 租约后台自动回收周期（毫秒）；&lt;=0 表示不启动后台线程，
     * 到期回收仍会在每个业务入口被触发执行。
     */
    private long leaseReaperIntervalMs = 1000;

    public String getEventLogPath() { return eventLogPath; }
    public void setEventLogPath(String eventLogPath) { this.eventLogPath = eventLogPath; }
    public double getOverTempDerateFactor() { return overTempDerateFactor; }
    public void setOverTempDerateFactor(double v) { this.overTempDerateFactor = v; }
    public double getShutdownReserveWh() { return shutdownReserveWh; }
    public void setShutdownReserveWh(double v) { this.shutdownReserveWh = v; }
    public long getLeaseReaperIntervalMs() { return leaseReaperIntervalMs; }
    public void setLeaseReaperIntervalMs(long v) { this.leaseReaperIntervalMs = v; }
}
