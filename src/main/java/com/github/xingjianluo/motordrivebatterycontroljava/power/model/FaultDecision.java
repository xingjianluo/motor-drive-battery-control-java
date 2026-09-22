package com.github.xingjianluo.motordrivebatterycontroljava.power.model;

/**
 * 故障处置结果。
 *
 * @param faultKey        故障实例键
 * @param duplicate       是否为重复上报（重复上报不改变状态、不重复扣减容量）
 * @param previousState   处置前系统状态
 * @param resultingState  处置后系统状态
 * @param explanation     判定依据
 */
public record FaultDecision(String faultKey,
                            boolean duplicate,
                            OperationalState previousState,
                            OperationalState resultingState,
                            String explanation) {
}
