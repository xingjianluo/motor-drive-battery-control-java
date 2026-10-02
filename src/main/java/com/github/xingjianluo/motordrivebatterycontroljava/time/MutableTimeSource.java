package com.github.xingjianluo.motordrivebatterycontroljava.time;

/**
 * 测试用可控逻辑时钟：从给定起点开始，只能手动向前推进，不可回拨，
 * 保证到期回收类用例无需真实等待即可确定复现。
 */
public final class MutableTimeSource implements TimeSource {

    private long nowMs;

    public MutableTimeSource(long startMs) {
        this.nowMs = startMs;
    }

    @Override
    public long nowMs() {
        return nowMs;
    }

    /** 将时钟向前推进 deltaMs（必须为正）。 */
    public void advance(long deltaMs) {
        if (deltaMs < 0) {
            throw new IllegalArgumentException("时钟不可回拨: " + deltaMs);
        }
        nowMs += deltaMs;
    }

    /** 直接设置到指定时刻（不得早于当前时刻）。 */
    public void setTime(long newMs) {
        if (newMs < nowMs) {
            throw new IllegalArgumentException("时钟不可回拨: " + newMs + " < " + nowMs);
        }
        nowMs = newMs;
    }
}
