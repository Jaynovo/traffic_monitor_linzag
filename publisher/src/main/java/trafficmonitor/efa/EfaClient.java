package trafficmonitor.efa;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * HTTP side of the upstream adapter: one stop in, one {@link StopDepartures} out.
 *
 * Owns nothing but transport. No scheduling, no retry, no backoff, no caching –
 * those are decisions of whoever calls this once a minute, because they are
 * degradation policy, and policy should not hide inside an HTTP wrapper.
 *
 * Thread-safe; create one and keep it (HttpClient pools connections).
 */
public final class EfaClient {

    private static final String DM_URL = "https://www.linzag.at/static/XML_DM_REQUEST";

    /** Identify yourself – an anonymous Java UA is the first thing an operator blocks. */
    private static final String USER_AGENT = "Traffic_Monitor/0.1 (private departure board)";

    /**
     * Whole request, headers to last body byte. The board runs on a 60 s cycle;
     * an answer that takes longer than this is an outage for our purposes.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final EfaParser parser;
    private final int upstreamLimit;

    public EfaClient() {
        this(20);
    }

    /**
     * @param upstreamLimit rows to request. Keep well above the contract's 8:
     *                      rows are re-sorted by realtime and some get dropped.
     */
    public EfaClient(int upstreamLimit) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.parser = new EfaParser();
        this.upstreamLimit = upstreamLimit;
    }

    /**
     * One blocking poll of one stop. EFA has no multi-stop batching, so N stops = N calls;
     * space them out in the caller rather than firing them back to back.
     *
     * @throws InterruptedException passed through untouched so a scheduler can shut down cleanly
     */
    public StopDepartures fetch(String stopId) throws EfaException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(buildUri(stopId))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip") // ~7x smaller; java.net.http does NOT inflate for us
                .GET()
                .build();

        HttpResponse<byte[]> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException e) { // includes HttpConnectTimeoutException
            throw new EfaException(EfaException.Kind.TIMEOUT, "no answer within " + REQUEST_TIMEOUT.toSeconds() + " s", e);
        } catch (IOException e) {
            throw new EfaException(EfaException.Kind.NETWORK, e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new EfaException(EfaException.Kind.HTTP, "status " + status);
        }

        return parser.parse(stopId, decodeBody(response));
    }

    private URI buildUri(String stopId) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("outputFormat", "JSON");
        q.put("locationServerActive", "1");
        q.put("stateless", "1");            // required since 2015, otherwise the server keeps session state
        q.put("type_dm", "any");
        q.put("name_dm", stopId);
        q.put("mode", "direct");            // skip the line-selection step, give us every line
        q.put("limit", Integer.toString(upstreamLimit));
        q.put("useRealtime", "1");
        q.put("lsShowTrainsExplicit", "1"); // S-Bahn/ÖBB rows are hidden by default
        String query = q.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return URI.create(DM_URL + "?" + query);
    }

    private static String decodeBody(HttpResponse<byte[]> response) throws EfaException {
        byte[] raw = response.body();
        boolean gzipped = response.headers().firstValue("Content-Encoding")
                .map(v -> v.toLowerCase().contains("gzip"))
                .orElse(false);
        if (gzipped) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                raw = in.readAllBytes();
            } catch (IOException e) {
                throw new EfaException(EfaException.Kind.PAYLOAD, "broken gzip body", e);
            }
        }
        return new String(raw, charsetOf(response));
    }

    /** Honour a declared charset (older EFA interfaces were ISO-8859-1); otherwise JSON means UTF-8. */
    private static Charset charsetOf(HttpResponse<?> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        int i = contentType.toLowerCase().indexOf("charset=");
        if (i >= 0) {
            String name = contentType.substring(i + "charset=".length()).split(";")[0].replace("\"", "").strip();
            try {
                return Charset.forName(name);
            } catch (IllegalArgumentException ignored) {
                // unknown or malformed name – fall through
            }
        }
        return StandardCharsets.UTF_8;
    }
}
