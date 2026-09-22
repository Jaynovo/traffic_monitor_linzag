package trafficmonitor.contract;

import org.junit.jupiter.api.Test;
import trafficmonitor.efa.Departure;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractMapperTest {

    /** Fixed "now" so every expectation is an offset from a known second. */
    private static final long NOW = 1758124800L;

    private final ContractMapper mapper =
            new ContractMapper(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    private static Departure dep(String line, String dest, long planned, OptionalLong realtime, boolean cancelled) {
        return new Departure(line, dest, planned, realtime, cancelled);
    }

    @Test
    void stampsVersionGenAndStopId() {
        DeparturesPayload p = mapper.toDepartures("60501400", List.of());
        assertEquals(1, p.v());
        assertEquals(NOW, p.gen());
        assertEquals("60501400", p.stop());
    }

    @Test
    void emptyInputIsAValidSnapshotNotAnError() {
        DeparturesPayload p = mapper.toDepartures("60501400", List.of());
        assertEquals(List.of(), p.dep());
    }

    @Test
    void sortsAscendingByEffectiveTime() {
        DeparturesPayload p = mapper.toDepartures("60501400", List.of(
                dep("3", "C", NOW + 300, null, false),
                dep("1", "A", NOW + 100, null, false),
                dep("2", "B", NOW + 200, null, false)));
        assertEquals(List.of("1", "2", "3"), p.dep().stream().map(DepartureRow::l).toList());
    }

    @Test
    void realtimeNotPlannedDecidesTheOrder() {
        // Planned order is A then B; a five-minute delay on A flips it.
        DeparturesPayload p = mapper.toDepartures("60501400", List.of(
                dep("1", "A", NOW + 100, OptionalLong.of(NOW + 400), false),
                dep("2", "B", NOW + 200, null, false)));
        assertEquals(List.of("2", "1"), p.dep().stream().map(DepartureRow::l).toList());
    }

    @Test
    void dropsRowsAlreadyPastButKeepsDepartingNow() {
        DeparturesPayload p = mapper.toDepartures("60501400", List.of(
                dep("past", "A", NOW - 1, null, false),
                dep("now", "B", NOW, null, false),
                dep("soon", "C", NOW + 60, null, false)));
        assertEquals(List.of("now", "soon"), p.dep().stream().map(DepartureRow::l).toList());
    }

    @Test
    void lateRealtimeRescuesARowWhosePlannedTimeHasPassed() {
        // Planned two minutes ago, running eight minutes late: still a future departure.
        DeparturesPayload p = mapper.toDepartures("60501400", List.of(
                dep("25", "Auwiesen", NOW - 120, OptionalLong.of(NOW + 360), false)));
        assertEquals(1, p.dep().size());
    }

    @Test
    void capsAtEightRowsKeepingTheEarliest() {
        List<Departure> many = new java.util.ArrayList<>();
        for (int i = 10; i >= 1; i--) {           // deliberately reverse order
            many.add(dep(String.valueOf(i), "Dest", NOW + i * 60L, null, false));
        }
        DeparturesPayload p = mapper.toDepartures("60501400", many);
        assertEquals(8, p.dep().size());
        assertEquals("1", p.dep().get(0).l());
        assertEquals("8", p.dep().get(7).l());
    }

    @Test
    void omitsRealtimeAndCancelledWhenAbsent() {
        DepartureRow row = mapper.toDepartures("60501400",
                List.of(dep("1", "A", NOW + 60, null, false))).dep().get(0);
        assertNull(row.r());
        assertNull(row.c());
    }

    @Test
    void zeroDelayRealtimeIsStillRealtime() {
        // r == p must survive: the presence of `r` is the realtime flag (§4).
        DepartureRow row = mapper.toDepartures("60501400",
                List.of(dep("1", "A", NOW + 60, OptionalLong.of(NOW + 60), false))).dep().get(0);
        assertEquals(NOW + 60, row.r());
    }

    @Test
    void cancelledBecomesOne() {
        DepartureRow row = mapper.toDepartures("60501400",
                List.of(dep("1", "A", NOW + 60, null, true))).dep().get(0);
        assertEquals(1, row.c());
    }

    @Test
    void capsDestinationAtThirtyTwoCodePoints() {
        String long33 = "A".repeat(33);
        DepartureRow row = mapper.toDepartures("60501400",
                List.of(dep("1", long33, NOW + 60, null, false))).dep().get(0);
        assertEquals(32, row.d().length());
    }

    @Test
    void umlautsCountAsOneCodePointNotTwoBytes() {
        String name = "Schörgenhub"; // 11 code points, 12 UTF-8 bytes
        DepartureRow row = mapper.toDepartures("60501400",
                List.of(dep("1", name, NOW + 60, null, false))).dep().get(0);
        assertEquals(name, row.d());
    }

    @Test
    void neverSplitsASurrogatePair() {
        // 31 ASCII + one astral character = 32 code points, 33 chars.
        String s = "A".repeat(31) + "\uD83D\uDE83";
        DepartureRow row = mapper.toDepartures("60501400",
                List.of(dep("1", s, NOW + 60, null, false))).dep().get(0);
        assertEquals(s, row.d());
        assertEquals(32, row.d().codePointCount(0, row.d().length()));
        assertTrue(Character.isSurrogatePair(row.d().charAt(31), row.d().charAt(32)));
    }

    @Test
    void trimsSurroundingWhitespace() {
        DepartureRow row = mapper.toDepartures("60501400",
                List.of(dep(" 25 ", "  Auwiesen  ", NOW + 60, null, false))).dep().get(0);
        assertEquals("25", row.l());
        assertEquals("Auwiesen", row.d());
    }

    @Test
    void stopsConfigKeepsOrderAndCapsLabels() {
        Map<String, String> stops = new LinkedHashMap<>();
        stops.put("60501400", "Eisenwerkstraße");
        stops.put("60501460", "Spallerhof");
        stops.put("60508040", "A".repeat(25));

        StopsPayload p = mapper.toStops(stops);
        assertEquals(1, p.v());
        assertEquals(List.of("60501400", "60501460", "60508040"),
                p.stops().stream().map(StopsPayload.StopEntry::id).toList());
        assertEquals("Eisenwerkstraße", p.stops().get(0).label());
        assertEquals(20, p.stops().get(2).label().length());
    }
}
