package trafficmonitor.efa;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalLong;

/**
 * Turns an XML_DM_REQUEST JSON body into {@link StopDepartures}.
 *
 * Pure function of its input: no clock, no network. Everything ugly about
 * EFA's JSON is contained in this one class:
 *
 *  - numbers arrive as strings ("minute": "7")
 *  - a collection with one element is emitted as a bare object, not a 1-item array
 *  - "departureList" is null (not []) when nothing departs
 *  - timestamps are Vienna wall-clock time split into fields, no offset
 *  - rows come in SCHEDULED order, so a late tram can sit above an on-time one
 */
public final class EfaParser {

    /** Upstream timestamps are local wall-clock time without an offset. */
    static final ZoneId UPSTREAM_ZONE = ZoneId.of("Europe/Vienna");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public StopDepartures parse(String requestedStopId, String body) throws EfaException {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new EfaException(EfaException.Kind.PAYLOAD, "body is not JSON: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new EfaException(EfaException.Kind.PAYLOAD, "expected a JSON object at top level");
        }

        // Resolve the stop FIRST. An unresolved stop also has departureList == null,
        // and we must never confuse "bad stop ID" with "no trams at 02:00".
        JsonNode point = root.path("dm").path("points").path("point");
        String resolvedId = point.path("ref").path("id").asText("");
        if (!point.isObject() || resolvedId.isBlank()) {
            throw new EfaException(EfaException.Kind.UNKNOWN_STOP,
                    "upstream did not resolve stop '" + requestedStopId + "' to exactly one stop");
        }
        String stopName = point.path("name").asText("");

        List<Departure> departures = new ArrayList<>();
        for (JsonNode row : asList(root.path("departureList"))) {
            Departure d = parseRow(row);
            if (d != null) {
                departures.add(d);
            }
        }
        // List.sort is stable: rows with equal effective time keep upstream order.
        departures.sort(Comparator.comparingLong(Departure::effectiveEpoch));

        return new StopDepartures(requestedStopId, resolvedId, stopName, departures);
    }

    /** @return null for rows we cannot represent (no line/destination, or no usable planned time). */
    private static Departure parseRow(JsonNode row) {
        if (!row.isObject()) {
            return null;
        }
        JsonNode serving = row.path("servingLine");
        String line = serving.path("number").asText("").strip();
        if (line.isEmpty()) {
            line = serving.path("symbol").asText("").strip();
        }
        String destination = serving.path("direction").asText("").strip();

        OptionalLong planned = toEpoch(row.path("dateTime"));
        if (line.isEmpty() || destination.isEmpty() || planned.isEmpty()) {
            return null; // contract requires l, d and p – a row without them is unpublishable
        }

        OptionalLong realtime = toEpoch(row.path("realDateTime"));

        // Enum-ish string; seen as plain "TRIP_CANCELLED". contains() rather than equals()
        // so a combined value ("MONITORED|TRIP_CANCELLED") still counts.
        boolean cancelled = row.path("realtimeTripStatus").asText("").toUpperCase().contains("TRIP_CANCELLED");

        return new Departure(line, destination, planned.getAsLong(), realtime, cancelled);
    }

    /**
     * {year, month, day, hour, minute} as Vienna wall-clock -> epoch seconds UTC.
     *
     * In the repeated hour of the autumn DST change atZone() picks the earlier
     * offset. Wrong for one hour a year, at 02:00-03:00 on a Sunday. Accepted.
     */
    private static OptionalLong toEpoch(JsonNode dt) {
        if (!dt.isObject()) {
            return OptionalLong.empty();
        }
        try {
            LocalDateTime local = LocalDateTime.of(
                    intField(dt, "year"), intField(dt, "month"), intField(dt, "day"),
                    intField(dt, "hour"), intField(dt, "minute"));
            return OptionalLong.of(local.atZone(UPSTREAM_ZONE).toEpochSecond());
        } catch (DateTimeException | NumberFormatException e) {
            return OptionalLong.empty();
        }
    }

    /** Works for both "7" and 7. Throws NumberFormatException when absent or garbage. */
    private static int intField(JsonNode node, String name) {
        return Integer.parseInt(node.path(name).asText("").strip());
    }

    /** EFA collapses 1-element collections to the element itself; null/missing means empty. */
    private static List<JsonNode> asList(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(out::add);
        } else if (node.isObject()) {
            out.add(node);
        }
        return out;
    }
}
