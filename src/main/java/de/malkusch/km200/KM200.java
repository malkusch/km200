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
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

    /**
     * Configure the KM200 API.
     * 
     * This will also issue a silent query to /system to verify that you
     * configuration is correct.
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

    private static record UpdateString(String value) {
    }

    public void update(String path, String value) throws KM200Exception, IOException, InterruptedException {
        var update = new UpdateString(value);
        update(path, update);
    }

    private static DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

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
        var response = http.send(request, BodyHandlers.ofString());

        if (!(response.statusCode() >= 200 && response.statusCode() < 300)) {
            throw new KM200Exception(
                    String.format("Failed to update %s [%d]: %s", path, response.statusCode(), response.body()));
        }
    }

    public String query(String path) throws KM200Exception, IOException, InterruptedException {
        var request = request(path).GET().build();
        var response = http.send(request, BodyHandlers.ofByteArray());

        switch (response.statusCode()) {
        case 200:
            break;
        case 404:
            throw new KM200Exception.NotFound("Query " + path + " was not found");
        default:
            throw new KM200Exception("Query " + path + " failed with response code " + response.statusCode());
        }

        var encrypted = response.body();
        if (encrypted == null) {
            throw new KM200Exception("No response when querying " + path);
        }
        var decrypted = comm.decodeMessage(device, encrypted);
        if (decrypted == null) {
            throw new KM200Exception("Could not decrypt query " + path);
        }
        return decrypted;
    }

    public double queryDouble(String path) throws KM200Exception, IOException, InterruptedException {
        var json = queryJson(path);
        return json.get("value").asDouble();
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
