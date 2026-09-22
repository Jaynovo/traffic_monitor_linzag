package trafficmonitor.contract;

import java.util.List;

/**
 * Payload of {@code transit/config/stops} — contract §5.
 *
 * <p>List order is the display's button cycle order.
 *
 * @param v     contract version
 * @param stops ordered stop entries
 */
public record StopsPayload(int v, List<StopEntry> stops) {

    /**
     * @param id    stop ID; must match a {@code transit/departures/<id>} topic
     * @param label display name, capped at 20 code points
     */
    public record StopEntry(String id, String label) {
    }
}
