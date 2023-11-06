package de.malkusch.km200;

import static java.net.http.HttpClient.newBuilder;
import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.malkusch.km200.KM200Exception.ServerError;
import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.FailsafeExecutor;
import dev.failsafe.RetryPolicy;

/**
 * This is an API for Bosch/Buderus/Junkers heaters with a KM200 gateway.
 * 
 * Although code wise this class is thread safe, it is highly recommended to not
 * use it concurrently. Chances are that your KM200 gateway itself is not thread
 * safe.
 */
public final class KM200 {

    private final KM200Device device;
    private final KM200Comm comm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final Duration timeout;
    private final String uri;

    private static final int RETRY_COUNT = 3;
    private static final Duration RETRY_DELAY_MIN = Duration.ofSeconds(1);
    private static final Duration RETRY_DELAY_MAX = Duration.ofSeconds(2);

    @SafeVarargs
    private static <T> FailsafeExecutor<T> buildRetry(Class<? extends Throwable>... exceptions) {
        return Failsafe.with( //
                RetryPolicy.<T> builder() //
                        .handle(exceptions) //
                        .withMaxRetries(RETRY_COUNT) //
                        .withDelay(RETRY_DELAY_MIN, RETRY_DELAY_MAX) //
                        .build());
    }

    /**
     * Configure the KM200 API.
     * 
     * This will also issue a silent query to /system to verify that you
     * configuration is correct.
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
     * @param uri
     *            The base URI of your KM200 e.g. http://192.168.0.44
     * @param timeout
     *            An IO timeout for requests to your heater
     * @param gatewayPassword
     *            The constant gateway password which you need to read out from
     *            your heater's display e.g. 1234-5678-90ab-cdef
     * @param privatePassword
     *            The private password which you did assign in the app. If you
     *            forgot your private password you can start the "reset internet
     *            password" flow in the menu of your heater and then reassign a
     *            new passwort in the app.
     * @param salt
     *            The salt in the hexadecimal representation e.g. "12a0b2…"
     * 
     * @throws KM200Exception
     * @throws IOException
     * @throws InterruptedException
     */
    public KM200(String uri, Duration timeout, String gatewayPassword, String privatePassword, String salt)
            throws KM200Exception, IOException, InterruptedException {

        assertNotBlank(uri, "uri must not be blank");
        requireNonNull(timeout);
        assertNotBlank(gatewayPassword, "gatewayPassword must not be blank");
        assertNotBlank(privatePassword, "privatePassword must not be blank");
        assertNotBlank(salt, "salt must not be blank");

        var device = new KM200Device();
        device.setCharSet("UTF-8");
        device.setGatewayPassword(gatewayPassword.replace("-", ""));
        device.setPrivatePassword(privatePassword);
        device.setIP4Address(uri);
        device.setMD5Salt(salt);
        device.setInited(true);
        this.device = device;

        this.comm = new KM200Comm();
        this.timeout = timeout;
        this.uri = uri.replaceAll("/*$", "");
        this.http = newBuilder().connectTimeout(timeout).cookieHandler(new CookieManager()).followRedirects(ALWAYS)
                .build();

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

    private final FailsafeExecutor<HttpResponse<String>> retryUpdate = buildRetry(ServerError.class);

    private void update(String path, Object update) throws KM200Exception, IOException, InterruptedException {
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
        var request = request(path).POST(BodyPublishers.ofByteArray(encrypted)).build();
        var response = sendWithRetries(retryUpdate, request, BodyHandlers.ofString());

        if (!(response.statusCode() >= 200 && response.statusCode() < 300)) {
            throw new KM200Exception(
                    String.format("Failed to update %s [%d]: %s", path, response.statusCode(), response.body()));
        }
    }

    private final FailsafeExecutor<HttpResponse<byte[]>> retryQuery = buildRetry(IOException.class, ServerError.class);;

    public String query(String path) throws KM200Exception, IOException, InterruptedException {
        assertNotBlank(path, "Path must not be blank");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("Path must start with a leading /");
        }

        var request = request(path).GET().build();
        var response = sendWithRetries(retryQuery, request, BodyHandlers.ofByteArray());
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

    private <T> HttpResponse<T> sendWithRetries(FailsafeExecutor<HttpResponse<T>> retry, HttpRequest request,
            BodyHandler<T> bodyHandler) throws IOException, InterruptedException {

        try {
            return retry.get(() -> send(request, bodyHandler));

        } catch (FailsafeException e) {
            var cause = e.getCause();
            if (cause instanceof IOException) {
                throw (IOException) cause;

            } else if (cause instanceof InterruptedException) {
                throw (InterruptedException) cause;

            } else if (cause instanceof KM200Exception) {
                throw (KM200Exception) cause;

            } else {
                throw e;
            }
        }
    }

    private <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException, KM200Exception {

        var response = http.send(request, bodyHandler);
        return switch (response.statusCode()) {
        case 200 -> response;
        case 403 -> throw new KM200Exception.Forbidden(request.uri() + " is forbidden");
        case 404 -> throw new KM200Exception.NotFound(request.uri() + " was not found");
        case 423 -> throw new KM200Exception.Locked(request.uri() + " was locked");
        case 500 -> throw new KM200Exception.ServerError(request.uri() + " resulted in a server error");
        default -> throw new KM200Exception(request.uri() + " failed with response code " + response.statusCode());
        };
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

    static final String USER_AGENT = "TeleHeater/2.2.3";

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(uri + path)) //
                .setHeader("User-Agent", USER_AGENT) //
                .setHeader("Accept", "application/json") //
                .timeout(timeout);
    }

    private static void assertNotBlank(String var, String message) {
        if (requireNonNull(var).isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }
}
