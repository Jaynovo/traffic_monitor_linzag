package trafficmonitor.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import trafficmonitor.contract.ContractJson;
import trafficmonitor.contract.DeparturesPayload;
import trafficmonitor.contract.StatusPayload;
import trafficmonitor.contract.StopsPayload;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;

/**
 * Publishes contract version 1 payloads over a <em>persistent</em> MQTT connection.
 *
 * <p>The connection is opened once in {@link #connect} and held for the process
 * lifetime. This is not an optimisation: it is what makes the Last Will and
 * Testament work. A publisher that connects per message disconnects cleanly every
 * time, the broker discards the will, and the retained status keeps asserting
 * {@code ok: true} after the process is gone — contract §8.1.
 *
 * <p>Failure policy: a transport failure is logged and reported as {@code false},
 * never thrown. Contract §8.4 wants the existing retained message to age rather
 * than be replaced by an error marker, and a throw out of the publish path would
 * take down the scheduler loop that is the only thing able to recover.
 *
 * <p>Thread confinement: intended to be driven from one scheduler thread. Paho's
 * blocking client is itself thread-safe, but the publish ordering guarantees of
 * §8.2 are not, so don't fan this out.
 */
public final class MqttPublisher implements AutoCloseable {

    private static final Logger LOG = System.getLogger(MqttPublisher.class.getName());

    /**
     * §4 payload ceiling to warn at. Not in the contract yet; the display's MQTT
     * client has a fixed receive buffer and silently drops anything larger, which
     * on the device looks identical to a broker fault. Warn here, where it is
     * diagnosable, rather than there, where it isn't.
     */
    private static final int SIZE_WARN_BYTES = 1024;

    private final String brokerUri;
    private final String clientId;
    private final int keepAliveSeconds;

    private MqttClient client;
    private Runnable onConnected = () -> {
    };

    /**
     * @param brokerUri        e.g. {@code tcp://192.168.0.191:1883}
     * @param clientId         stable across restarts on purpose. A generated ID makes
     *                         a double-started publisher invisible; a fixed one makes
     *                         the two instances fight over the session, which is loud
     *                         and therefore diagnosable.
     * @param keepAliveSeconds the broker declares the publisher dead — and fires the
     *                         will — after roughly 1.5x this. Keep it at or below the
     *                         publish interval, or the display's {@code AGING} state
     *                         arrives before the {@code ok: false} that explains it.
     */
    public MqttPublisher(String brokerUri, String clientId, int keepAliveSeconds) {
        this.brokerUri = brokerUri;
        this.clientId = clientId;
        this.keepAliveSeconds = keepAliveSeconds;
    }

    /**
     * Runs after every successful connect, including automatic reconnects.
     *
     * <p>Set this to republish config and status (§8.2). It matters most on
     * reconnect: if the will fired during the outage, the retained status on the
     * broker now says {@code ok: false}, and nothing else will correct it until the
     * next interval. Republishing on connect closes that window.
     */
    public void setOnConnected(Runnable onConnected) {
        this.onConnected = onConnected;
    }

    public void connect() throws MqttException {
        client = new MqttClient(brokerUri, clientId, new MemoryPersistence());

        MqttConnectOptions opts = new MqttConnectOptions();
        opts.setCleanSession(true);          // publisher subscribes to nothing; no session state worth keeping
        opts.setAutomaticReconnect(true);
        opts.setKeepAliveInterval(keepAliveSeconds);
        opts.setConnectionTimeout(10);
        opts.setMaxReconnectDelay(30_000);   // default 128 s is a long time to be dark on a LAN
        opts.setWill(Topics.PUBLISHER_STATUS,
                ContractJson.LWT.getBytes(StandardCharsets.UTF_8),
                1, true);                    // QoS 1, retained — §6

        client.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverUri) {
                LOG.log(Level.INFO, reconnect ? "MQTT reconnected to {0}" : "MQTT connected to {0}", serverUri);
                try {
                    onConnected.run();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "onConnected hook failed", e);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                LOG.log(Level.WARNING, "MQTT connection lost", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });

        client.connect(opts);
    }

    /** §3: retained, QoS 0 — the next snapshot supersedes this one entirely. */
    public boolean publishDepartures(DeparturesPayload payload) {
        return publish(Topics.departures(payload.stop()), serialise(() -> ContractJson.departures(payload)), 0);
    }

    /** §3: retained, QoS 1 — rare message, a silent loss stays wrong for a long time. */
    public boolean publishStops(StopsPayload payload) {
        return publish(Topics.CONFIG_STOPS, serialise(() -> ContractJson.stops(payload)), 1);
    }

    /** §3: retained, QoS 1. */
    public boolean publishStatus(StatusPayload payload, long gen) {
        return publish(Topics.PUBLISHER_STATUS, serialise(() -> ContractJson.status(payload, gen)), 1);
    }

    /**
     * Clears a retained departures topic by publishing a zero-length retained payload.
     *
     * <p>Call this when a stop leaves {@code config/stops}. §8.6 forbids publishing to
     * a stop that is no longer configured, but the retained message from before the
     * change survives on the broker forever, and the display subscribes with a
     * wildcard — so without this the removed stop reappears on the next display boot.
     */
    public boolean clearDepartures(String stopId) {
        return publish(Topics.departures(stopId), new byte[0], 1);
    }

    /**
     * Publishes the offline status explicitly, for clean shutdown.
     *
     * <p>A graceful {@code disconnect()} suppresses the will — that is what graceful
     * means — so {@code systemctl stop} would otherwise leave a retained {@code ok: true}
     * behind and reintroduce exactly the bug the LWT exists to prevent. Wire this into
     * a shutdown hook before {@link #close}.
     */
    public boolean publishOffline() {
        return publish(Topics.PUBLISHER_STATUS, ContractJson.LWT.getBytes(StandardCharsets.UTF_8), 1);
    }

    private boolean publish(String topic, byte[] payload, int qos) {
        if (payload == null) {
            return false;
        }
        if (payload.length > SIZE_WARN_BYTES) {
            LOG.log(Level.WARNING, "Payload for {0} is {1} bytes, above the {2} byte display buffer assumption",
                    topic, payload.length, SIZE_WARN_BYTES);
        }
        try {
            client.publish(topic, payload, qos, true);   // retained everywhere — §3
            return true;
        } catch (MqttException e) {
            // Includes "client not connected" while automatic reconnect is backing off.
            // Deliberately not fatal: the retained message ages and the display says so.
            LOG.log(Level.WARNING, "Publish to " + topic + " failed", e);
            return false;
        }
    }

    private interface Serialiser {
        String write() throws JsonProcessingException;
    }

    private static byte[] serialise(Serialiser s) {
        try {
            return s.write().getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            // A payload that cannot be serialised is a bug in the mapping layer, not a
            // runtime condition, and silently publishing nothing would hide it.
            throw new IllegalStateException("Contract payload failed to serialise", e);
        }
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    @Override
    public void close() {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect();
            }
        } catch (MqttException e) {
            LOG.log(Level.WARNING, "Disconnect failed", e);
        } finally {
            try {
                client.close();
            } catch (MqttException e) {
                LOG.log(Level.WARNING, "Close failed", e);
            }
        }
    }
}
