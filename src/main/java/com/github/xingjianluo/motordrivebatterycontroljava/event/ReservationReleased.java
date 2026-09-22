package com.github.xingjianluo.motordrivebatterycontroljava.event;

import com.github.xingjianluo.motordrivebatterycontroljava.event.DomainEvent;

/** 撤销事件：返还 reservedWh 能量。重复撤销不会产生该事件（账本幂等拦截）。 */
public record ReservationReleased(long sequence,
                                  long timestampMs,
                                  String requestId,
                                  String sourceId,
                                  double refundedWh) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "RELEASED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId, Double.toString(refundedWh));
    }

    public static ReservationReleased fromFields(String[] f, long seq, long ts) {
        return new ReservationReleased(seq, ts, f[3], f[4], Double.parseDouble(f[5]));
    }
}
