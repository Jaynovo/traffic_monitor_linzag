package trafficmonitor.contract;

import trafficmonitor.efa.Departure;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Maps EFA fetch results onto MQTT contract version 1 payloads.
 *
 * <p>Pure: no I/O, no MQTT, no static clock. Everything that varies is either a
 * parameter or comes from the injected {@link Clock}, so the whole mapping layer
 * is testable without a broker and without waiting for wall-clock time to pass.
 *
 * <p>Responsibilities, all of them from the contract:
 * <ul>
 *   <li>§8.5 — sort ascending by effective departure time, drop rows already past</li>
 *   <li>§4 — cap at 8 rows, cap destinations at 32 code points</li>
 *   <li>§4 — omit {@code r} when there is no realtime data, omit {@code c} when not cancelled</li>
 *   <li>§5 — cap stop labels at 20 code points, preserve configured order</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> here: truncation to screen width, umlaut folding,
 * countdown minutes. Those are firmware concerns (§4 design notes).
 */
public final class ContractMapper {

    public static final int CONTRACT_VERSION = 1;

    /** §4: max rows per departures message. */
    public static final int MAX_ROWS = 8;

    /** §4: destination cap, in Unicode code points. */
    public static final int MAX_DESTINATION = 32;

    /** §5: stop label cap, in Unicode code points. */
    public static final int MAX_LABEL = 20;

    /**
     * Ascending by effective time; planned time and then line label break ties so
     * that two rows departing in the same second keep a stable, reproducible order
     * across polls instead of shuffling on screen.
     */
    private static final Comparator<DepartureRow> BY_EFFECTIVE =
            Comparator.comparingLong(DepartureRow::effective)
                    .thenComparingLong(DepartureRow::p)
                    .thenComparing(DepartureRow::l, Comparator.nullsLast(String::compareTo));

    private final Clock clock;

    public ContractMapper(Clock clock) {
        this.clock = clock;
    }

    public ContractMapper() {
        this(Clock.systemUTC());
    }

    /**
     * Builds one departures snapshot.
     *
     * <p>The stop ID is passed in rather than read off the fetch result: it must be
     * identical to the topic suffix, and EFA is free to resolve a requested ID to a
     * neighbouring or parent stop. Compare {@code asked} against {@code got} in the
     * publisher and refuse to publish on a mismatch — silently republishing another
     * stop's departures under this topic is worse than publishing nothing.
     *
     * @param stopId     the configured stop ID, used verbatim as topic suffix and as {@code stop}
     * @param departures parsed EFA departures, any order, may be empty
     * @return a payload that satisfies contract §4; an empty {@code dep} list is a valid result
     */
    public DeparturesPayload toDepartures(String stopId, List<Departure> departures) {
        long gen = clock.instant().getEpochSecond();

        List<DepartureRow> rows = new ArrayList<>(departures.size());
        for (Departure d : departures) {
            DepartureRow row = toRow(d);
            // §8.5: a row whose effective time has already passed is noise on a
            // board that only shows the next few minutes. Equality survives:
            // "departing now" is still worth a line.
            if (row.effective() >= gen) {
                rows.add(row);
            }
        }
        rows.sort(BY_EFFECTIVE);
        if (rows.size() > MAX_ROWS) {
            rows = new ArrayList<>(rows.subList(0, MAX_ROWS));
        }
        return new DeparturesPayload(CONTRACT_VERSION, gen, stopId, List.copyOf(rows));
    }

    /**
     * Builds the stop config payload. Iteration order of the argument is the button
     * cycle order on the display, so pass an ordered map.
     *
     * @param idToLabel stop ID → display label, in the order the display should cycle
     */
    public StopsPayload toStops(Map<String, String> idToLabel) {
        List<StopsPayload.StopEntry> entries = new ArrayList<>(idToLabel.size());
        idToLabel.forEach((id, label) -> entries.add(
                new StopsPayload.StopEntry(id, cap(label, MAX_LABEL))));
        return new StopsPayload(CONTRACT_VERSION, List.copyOf(entries));
    }

    // ---------------------------------------------------------------------
    // Adapter seam: the only place in this layer that touches the EFA types.
    // ---------------------------------------------------------------------
    private static DepartureRow toRow(Departure d) {
        // OptionalLong → nullable Long: §4 omits `r` entirely when there is no prediction.
        Long realtime = d.realtimeEpoch().isPresent() ? d.realtimeEpoch().getAsLong() : null;
        boolean cancelled = d.cancelled();
        return DepartureRow.of(
                d.line() == null ? "" : d.line().trim(),   // §4 sets no cap on `l`
                cap(d.destination(), MAX_DESTINATION),
                d.plannedEpoch(),
                realtime,
                cancelled);
    }

    /**
     * Trims and truncates to {@code maxCodePoints} Unicode code points.
     *
     * <p>Code points, not {@code char}s: {@code String.length()} counts UTF-16 units,
     * so a naive {@code substring} can split a surrogate pair and emit an unpaired
     * half, which is not valid UTF-8 and which Jackson will happily encode as a
     * replacement character the display then renders as a box. Austrian stop names
     * are BMP-only today, but the failure mode is silent and the guard is three lines.
     *
     * <p>Note this still counts code points, not grapheme clusters or display width —
     * 32 code points of umlauts is up to 64 UTF-8 bytes, which matters when sizing the
     * ESP32 receive buffer.
     */
    static String cap(String s, int maxCodePoints) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= maxCodePoints) {
            return t; // fast path: length() is an upper bound on codePointCount()
        }
        if (t.codePointCount(0, t.length()) <= maxCodePoints) {
            return t;
        }
        return t.substring(0, t.offsetByCodePoints(0, maxCodePoints));
    }
}
