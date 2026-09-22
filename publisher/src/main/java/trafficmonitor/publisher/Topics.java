package trafficmonitor.publisher;

/** Topic names from contract §3. The single place they are spelled out. */
public final class Topics {

    public static final String DEPARTURES_PREFIX = "transit/departures/";
    public static final String CONFIG_STOPS = "transit/config/stops";
    public static final String PUBLISHER_STATUS = "transit/publisher/status";

    private Topics() {
    }

    public static String departures(String stopId) {
        return DEPARTURES_PREFIX + stopId;
    }
}
