package trafficmonitor.efa;

import java.util.List;

/**
 * Result of one successful poll of one stop.
 *
 * @param requestedStopId the ID we asked for (= MQTT topic suffix)
 * @param resolvedStopId  the ID upstream says it answered for; should be equal,
 *                        and the publisher should refuse to publish if it is not
 * @param stopName        upstream display name, e.g. "Linz/Donau, Hauptbahnhof"
 * @param departures      ascending by effective time; may legitimately be empty
 */
public record StopDepartures(
        String requestedStopId,
        String resolvedStopId,
        String stopName,
        List<Departure> departures) {

    public StopDepartures {
        departures = List.copyOf(departures);
    }
}
