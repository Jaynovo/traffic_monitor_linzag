package trafficmonitor.contract;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialises contract payloads. The only place in the publisher that knows JSON.
 *
 * <p>Two rules from §2 live here rather than in the DTOs:
 * <ul>
 *   <li><b>Omission over null</b> — an absent optional field is an absent key, never
 *       {@code "r": null}. Enforced globally by {@code NON_NULL}.</li>
 *   <li><b>Except {@code up_err}</b>, which must always be present so that "checked,
 *       no error" stays distinguishable from "field missing". Since the global rule
 *       would drop it, the status message is built from an explicit ordered map. That
 *       map is also what maps {@code intervalSeconds} onto the wire key {@code int},
 *       which cannot be a Java identifier.</li>
 * </ul>
 */
public final class ContractJson {

    private static final ObjectMapper OMIT_NULLS = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    /**
     * Separate instance on purpose: {@code NON_NULL} also suppresses null <em>map
     * values</em>, so serialising the status map with {@link #OMIT_NULLS} would drop
     * {@code up_err} exactly in the case the contract requires it to be visible.
     */
    private static final ObjectMapper KEEP_NULLS = JsonMapper.builder()
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .build();

    /** §6: static LWT payload, registered on connect, retained. No {@code gen} by design. */
    public static final String LWT = "{\"v\":1,\"ok\":false,\"up_err\":\"lwt\"}";

    private ContractJson() {
    }

    public static String departures(DeparturesPayload payload) throws JsonProcessingException {
        return OMIT_NULLS.writeValueAsString(payload);
    }

    public static String stops(StopsPayload payload) throws JsonProcessingException {
        return OMIT_NULLS.writeValueAsString(payload);
    }

    /**
     * @param gen epoch seconds at serialisation — passed in rather than stored on the
     *            record so that a single publish cycle can stamp status and departures
     *            with the same instant
     */
    public static String status(StatusPayload s, long gen) throws JsonProcessingException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("v", ContractMapper.CONTRACT_VERSION);
        m.put("gen", gen);
        m.put("ok", s.ok());
        m.put("int", s.intervalSeconds());
        m.put("up_ok", s.upOk());
        m.put("up_err", s.upErr());   // deliberately present even when null
        if (s.stops() != null) {
            m.put("stops", s.stops());
        }
        return KEEP_NULLS.writeValueAsString(m);
    }

    /**
     * UTF-8 payload size. Worth asserting against in the publisher: the display's
     * MQTT client has a fixed receive buffer, and an oversized message is dropped
     * silently at the ESP32 end, which looks exactly like a broker problem.
     */
    public static int byteSize(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8).length;
    }
}
