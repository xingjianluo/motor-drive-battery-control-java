package com.github.xingjianluo.motordrivebatterycontroljava.time;

/**
 * 逻辑时钟：所有租约到期判定都基于该时钟，而非直接调用 System.currentTimeMillis()。
 * 生产环境使用 {@link SystemTimeSource}；本地测试使用可手动推进的可控时钟，
 * 无需真实 sleep 即可稳定复现过期行为。
 */
public interface TimeSource {

    /** 当前逻辑时间（epoch 毫秒）。 */
    long nowMs();
}
