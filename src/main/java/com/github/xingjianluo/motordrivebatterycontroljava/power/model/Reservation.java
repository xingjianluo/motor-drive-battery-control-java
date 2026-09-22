package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 一条生效中的功率预留账本记录。
 *
 * @param requestId  请求标识
 * @param source     控制入口
 * @param watts      预留功率（裁剪后的授予值）
 */
public record Reservation(String requestId, String source, double watts) {
}
