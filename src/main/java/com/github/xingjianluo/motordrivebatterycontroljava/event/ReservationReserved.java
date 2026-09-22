package com.github.xingjianluo.motordrivebatterycontroljava.event;

import com.github.xingjianluo.motordrivebatterycontroljava.event.DomainEvent;

/** 预留成功事件：冻结 reservedWh 能量。 */
public record ReservationReserved(long sequence,
                                  long timestampMs,
                                  String requestId,
                                  String sourceId,
                                  double requestedPowerW,
                                  double grantedPowerW,
                                  long durationMs,
                                  double reservedWh) implements DomainEvent {

    @Override
    public String toLogLine() {
        return String.join("|",
                "RESERVED", Long.toString(sequence), Long.toString(timestampMs),
                requestId, sourceId,
                Double.toString(requestedPowerW), Double.toString(grantedPowerW),
                Long.toString(durationMs), Double.toString(reservedWh));
    }

    public static ReservationReserved fromFields(String[] f, long seq, long ts) {
        return new ReservationReserved(seq, ts, f[3], f[4],
                Double.parseDouble(f[5]), Double.parseDouble(f[6]),
                Long.parseLong(f[7]), Double.parseDouble(f[8]));
    }
}
