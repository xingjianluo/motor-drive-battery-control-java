package com.github.xingjianluo.motordrivebatterycontroljava.time;

/**
 * 时间源（毫秒）。生产用系统时钟；测试用可推进的虚拟时钟，
 * 使租约到期/续期等时间相关行为在本地可确定性复现，无需真实 sleep。
 */
@FunctionalInterface
public interface TimeSource {

    long nowMs();

    static TimeSource system() {
        return System::currentTimeMillis;
    }
}
