package com.github.xingjianluo.motordrivebatterycontroljava.event;

import com.github.xingjianluo.motordrivebatterycontroljava.event.DomainEvent;

/** 结算事件：按 actualConsumedWh 实扣，差额（reservedWh-actualConsumedWh）返还。 */
public record ReservationSettled(long sequence,
                                 long timestampMs,
                                 String requestId,
                                 String sourceId,
                                 double reservedWh,
                                 double actualConsumedWh,
                                 double refundedWh) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "SETTLED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId,
                Double.toString(reservedWh), Double.toString(actualConsumedWh),
                Double.toString(refundedWh));
    }

    public static ReservationSettled fromFields(String[] f, long seq, long ts) {
        return new ReservationSettled(seq, ts, f[3], f[4],
                Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                Double.parseDouble(f[7]));
    }
}
