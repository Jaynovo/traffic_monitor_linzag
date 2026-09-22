package trafficmonitor.contract;

import java.util.List;

/**
 * Payload of {@code transit/departures/<stopId>} — contract §4.
 *
 * <p>A complete snapshot. Every message fully replaces the previous one, which
 * is what makes QoS 0 and duplicate delivery harmless.
 *
 * @param v   contract version
 * @param gen epoch seconds at serialisation; the display anchors its countdowns on this
 * @param stop stop ID, identical to the topic suffix
 * @param dep  rows ascending by effective departure time, at most {@value ContractMapper#MAX_ROWS};
 *             an empty list is legitimate (late night), never an error signal
 */
public record DeparturesPayload(int v, long gen, String stop, List<DepartureRow> dep) {
}
