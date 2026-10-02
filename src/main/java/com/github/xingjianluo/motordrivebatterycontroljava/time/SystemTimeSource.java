package com.github.xingjianluo.motordrivebatterycontroljava.time;

/** 生产用逻辑时钟：直接读取墙钟。 */
public final class SystemTimeSource implements TimeSource {

    @Override
    public long nowMs() {
        return System.currentTimeMillis();
    }
}
