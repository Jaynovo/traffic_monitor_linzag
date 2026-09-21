package trafficmonitor.efa;

/**
 * Single checked exception for every way a poll can fail.
 *
 * The {@link Kind} is what ends up in {@code up_err} on the status topic,
 * so keep it coarse and stable. Detail goes in the message / log only.
 */
public class EfaException extends Exception {

    public enum Kind {
        /** Connect or response timeout. */
        TIMEOUT,
        /** DNS, TLS, connection reset – we never got an HTTP status. */
        NETWORK,
        /** Got an HTTP status, and it was not 2xx. */
        HTTP,
        /** 2xx, but the body is not the JSON shape we expect. */
        PAYLOAD,
        /** Valid response, but upstream could not resolve the stop ID. Config error, not an outage. */
        UNKNOWN_STOP
    }

    private static final long serialVersionUID = 1L;

    private final Kind kind;

    public EfaException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public EfaException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
