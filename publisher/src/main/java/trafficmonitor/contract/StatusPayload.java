package trafficmonitor.contract;

/**
 * Payload of {@code transit/publisher/status} — contract §6.
 *
 * <p>The wire key for the publish interval is {@code int}, which is a Java
 * keyword and therefore cannot be a record component. This record uses
 * {@code intervalSeconds}; {@link ContractJson#status} does the key mapping.
 * That is also why the status payload is serialised from an explicit map
 * rather than from this record directly.
 *
 * @param ok              publisher process alive and publishing
 * @param intervalSeconds nominal publish interval → wire key {@code int}
 * @param upOk            epoch seconds of the last successful upstream fetch → {@code up_ok}
 * @param upErr           reason for the most recent upstream failure, {@code null} if the last
 *                        attempt succeeded → {@code up_err}. Explicitly nullable: the key is
 *                        always present, unlike every other optional field in the contract.
 * @param stops           number of stops served, may be {@code null} (key omitted)
 */
public record StatusPayload(boolean ok, int intervalSeconds, long upOk, String upErr, Integer stops) {

    /** Upstream error vocabulary from contract §6. */
    public static final String ERR_TIMEOUT = "timeout";
    public static final String ERR_PARSE = "parse";
    public static final String ERR_EMPTY = "empty";
    public static final String ERR_LWT = "lwt";

    /** {@code "http_502"} and friends. */
    public static String httpError(int statusCode) {
        return "http_" + statusCode;
    }
}
