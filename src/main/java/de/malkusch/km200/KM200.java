package de.malkusch.km200;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.malkusch.km200.KM200Exception.ServerError;
import de.malkusch.km200.http.Http;
import de.malkusch.km200.http.RetryHttp;
import de.malkusch.km200.http.SerializedHttp;
import de.malkusch.km200.http.UrlHttp;

/**
 * This is an API for Bosch/Buderus/Junkers heaters with a KM200 gateway.
 * 
 * Example:
 * 
 * <pre>
 * {@code
 * var uri = "http://192.168.0.44";
 * var gatewayPassword = "1234-5678-90ab-cdef";
 * var privatePassword = "secretExample";
 * var timeout = Duration.ofSeconds(5);
 * var salt = "1234567890aabbccddeeff11223344556677889900aabbccddeeffa0a1a2b2d3";
 * 
 * var km200 = new KM200(uri, timeout, gatewayPassword, privatePassword, salt);
 * 
 * // Read the heater's time
 * var time = km200.queryString("/gateway/DateTime");
 * System.out.println(time);
 * 
 * // Update the heater's time
 * km200.update("/gateway/DateTime", LocalDateTime.now());
 * 
 * // Explore the heater's endpoints
 * km200.endpoints().forEach(System.out::println);
 * }
 * </pre>
 * 
 * Code wise this class is thread safe, it is highly recommended to not use it
 * concurrently. Your KM200 gateway itself is not thread safe. In order to
 * protect users from wrong usage, this API will serialize all requests, i.e.
 * concurrent requests will happen sequentially.
 */
public final class KM200 {

    private final KM200Device device;
    private final KM200Comm comm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Http queryHttp;
    private final Http updateHttp;

    public static final int RETRY_DEFAULT = 3;
    public static final int RETRY_DISABLED = 0;

    static final String USER_AGENT = "TeleHeater/2.2.3";

    /**
     * Configure the KM200 API with a default retry of {@link #RETRY_DEFAULT}.
     * 
     * @see #KM200(String, int, Duration, String, String, String)
     **/
    public KM200(String uri, Duration timeout, String gatewayPassword, String privatePassword, String salt)
            throws KM200Exception, IOException, InterruptedException {

        this(uri, RETRY_DEFAULT, timeout, gatewayPassword, privatePassword, salt);
    }

    /**
     * Configure the KM200 API.
     * 
     * This will also issue a silent query to /system to verify that you
     * configuration is correct.
     * 
     * @param uri
     *            The base URI of your KM200 e.g. http://192.168.0.44
     * @param retries
     *            The amount of retries. Set to {@link #RETRY_DISABLED} to
     *            disable retrying. Retries add a waiting delay between each
     *            retry of several seconds, because the km200 recovers very
     *            slowly.
     * @param timeout
     *            An IO timeout for individual requests to your heater. Retries
     *            might block the API longer than this timeout.
     * @param gatewayPassword
     *            The constant gateway password which you need to read out from
     *            your heater's display e.g. 1234-5678-90ab-cdef
     * @param privatePassword
     *            The private password which you did assign in the app. If you
     *            forgot your private password you can start the "reset internet
     *            password" flow in the menu of your heater and then reassign a
     *            new password in the app.
     * @param salt
     *            The salt in the hexadecimal representation e.g. "12a0b2…"
     * 
     * @throws KM200Exception
     * @throws IOException
     * @throws InterruptedException
     */
    public KM200(String uri, int retries, Duration timeout, String gatewayPassword, String privatePassword, String salt)
            throws KM200Exception, IOException, InterruptedException {

        assertNotBlank(uri, "uri must not be blank");
        requireNonNull(timeout);
        assertNotBlank(gatewayPassword, "gatewayPassword must not be blank");
        assertNotBlank(privatePassword, "privatePassword must not be blank");
        assertNotBlank(salt, "salt must not be blank");
        assertNotNegative(retries, "retries must not be negative");

        var device = new KM200Device();
        device.setCharSet("UTF-8");
        device.setGatewayPassword(gatewayPassword.replace("-", ""));
        device.setPrivatePassword(privatePassword);
        device.setIP4Address(uri);
        device.setMD5Salt(salt);
        device.setInited(true);
        this.device = device;
        this.comm = new KM200Comm();

        {
            // Http http = new JdkHttp(uri.replaceAll("/*$", ""), USER_AGENT,
            // timeout);
            Http http = new UrlHttp(uri.replaceAll("/*$", ""), USER_AGENT, timeout);

            /*
             * The KM200 itself is not thread safe. This proxy serializes all
             * requests to protect users from a wrong concurrent usage of this
             * API.
             */
            http = new SerializedHttp(http);

            queryHttp = new RetryHttp(http, retries, IOException.class, ServerError.class);
            updateHttp = new RetryHttp(http, retries, ServerError.class);
        }

        query("/system");
    }

    private final KM200Endpoint.Factory endpointFactory = new KM200Endpoint.Factory(this, mapper);

    public Stream<KM200Endpoint> endpoints() throws KM200Exception, IOException, InterruptedException {
        return endpointFactory.build();
    }

    private static record UpdateString(String value) {
    }

    public void update(String path, String value) throws KM200Exception, IOException, InterruptedException {
        var update = new UpdateString(value);
        update(path, update);
    }

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public void update(String path, LocalDateTime time) throws KM200Exception, IOException, InterruptedException {
        update(path, time.format(DATE_TIME_FORMATTER));
    }

    private static record UpdateFloat(BigDecimal value) {
    }

    public void update(String path, int value) throws KM200Exception, IOException, InterruptedException {
        update(path, new BigDecimal(value));
    }

    public void update(String path, BigDecimal value) throws KM200Exception, IOException, InterruptedException {
        var update = new UpdateFloat(value);
        update(path, update);
    }

    private void update(String path, Object update) throws KM200Exception, IOException, InterruptedException {
        assertPath(path);

        String json = null;
        try {
            json = mapper.writeValueAsString(update);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to update " + path, e);
        }
        var encrypted = comm.encodeMessage(device, json);
        if (encrypted == null) {
            throw new KM200Exception("Could not encrypt update " + json);
        }
        var response = updateHttp.post(path, encrypted);

        if (!(response.status() >= 200 && response.status() < 300)) {
            throw new KM200Exception(
                    String.format("Failed to update %s [%d]: %s", path, response.status(), response.body()));
        }
    }

    public String query(String path) throws KM200Exception, IOException, InterruptedException {
        assertPath(path);

        var response = queryHttp.get(path);
        var encrypted = response.body();
        if (encrypted == null) {
            throw new KM200Exception("No response when querying " + path);
        }
        var decrypted = comm.decodeMessage(device, encrypted);
        if (decrypted == null) {
            throw new KM200Exception("Could not decrypt query " + path);
        }
        if (path.equals("/gateway/firmware")) {
            return decrypted;
        } else {
            if (!decrypted.startsWith("{")) {
                throw new KM200Exception(
                        String.format("Could not decrypt query %s. Body was:\n%s\n\n Decrypted was:\n%s", path,
                                encrypted, decrypted));
            }
        }
        return decrypted;
    }

    public double queryDouble(String path) throws KM200Exception, IOException, InterruptedException {
        var json = queryJson(path);
        return json.get("value").asDouble();
    }

    public BigDecimal queryBigDecimal(String path) throws KM200Exception, IOException, InterruptedException {
        var json = queryJson(path);
        return json.get("value").decimalValue();
    }

    public String queryString(String path) throws KM200Exception, IOException, InterruptedException {
        var json = queryJson(path);
        return json.get("value").asText();
    }

    private JsonNode queryJson(String path) throws KM200Exception, IOException, InterruptedException {
        try {
            return mapper.readTree(query(path));
        } catch (JsonProcessingException e) {
            throw new KM200Exception("Could not parse JSON from query " + path, e);
        }
    }

    private static void assertPath(String path) {
        assertNotBlank(path, "Path must not be blank");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with a leading /");
        }
    }

    private static void assertNotBlank(String var, String message) {
        if (requireNonNull(var).isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void assertNotNegative(int var, String message) {
        if (var < 0) {
            throw new IllegalArgumentException(message);
        }
    }
}
