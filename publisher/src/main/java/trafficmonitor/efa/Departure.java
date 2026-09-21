package trafficmonitor.efa;

import java.util.OptionalLong;

/**
 * One departure row, already in the shape the MQTT contract needs
 * (epoch seconds UTC, realtime optional, cancelled as a flag).
 *
 * Deliberately NOT the wire format: key shortening (l/d/p/r/c), the
 * 32-char destination cap and the 8-row cap belong to the publisher's
 * mapping step, not to the upstream adapter.
 */
public record Departure(
        String line,
        String destination,
        long plannedEpoch,
        OptionalLong realtimeEpoch,
        boolean cancelled) {

    /** Contract §4: effective time is realtime if present, else planned. */
    public long effectiveEpoch() {
        return realtimeEpoch.orElse(plannedEpoch);
    }
}
