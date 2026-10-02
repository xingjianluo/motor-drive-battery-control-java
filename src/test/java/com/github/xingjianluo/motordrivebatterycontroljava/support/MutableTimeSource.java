package com.github.xingjianluo.motordrivebatterycontroljava.support;

import com.github.xingjianluo.motordrivebatterycontroljava.time.TimeSource;

/**
 * 可手动推进的虚拟时钟：让租约到期/续期在单测中确定性复现，无需真实 sleep。
 */
public final class MutableTimeSource implements TimeSource {

    private long nowMs;

    public MutableTimeSource(long startMs) {
        this.nowMs = startMs;
    }

    @Override
    public synchronized long nowMs() {
        return nowMs;
    }

    public synchronized void advance(long deltaMs) {
        nowMs += deltaMs;
    }
}
