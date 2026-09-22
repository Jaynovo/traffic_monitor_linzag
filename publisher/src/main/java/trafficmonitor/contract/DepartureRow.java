package trafficmonitor.contract;

/**
 * One row of the {@code dep} array in {@code transit/departures/<stopId>}.
 *
 * <p>Component names are the wire keys. Jackson serialises records by component
 * name, so this record <em>is</em> the schema — renaming a component is a
 * contract change and bumps {@code v}.
 *
 * <ul>
 *   <li>{@code l} — line label as displayed</li>
 *   <li>{@code d} — destination, already capped at 32 code points</li>
 *   <li>{@code p} — planned departure, epoch seconds</li>
 *   <li>{@code r} — realtime departure, epoch seconds; {@code null} means the row
 *       is scheduled-only and the key is omitted from the payload</li>
 *   <li>{@code c} — {@code 1} if cancelled; {@code null} otherwise, key omitted</li>
 * </ul>
 */
public record DepartureRow(String l, String d, long p, Long r, Integer c) {

    /** Effective departure time: realtime if present, planned otherwise. */
    public long effective() {
        return r != null ? r : p;
    }

    /** Convenience factory that maps a {@code false} cancellation to an omitted key. */
    public static DepartureRow of(String line, String destination, long planned, Long realtime, boolean cancelled) {
        return new DepartureRow(line, destination, planned, realtime, cancelled ? 1 : null);
    }
}
