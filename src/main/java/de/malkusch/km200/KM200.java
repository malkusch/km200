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

// See https://github.com/hlipka/buderus2mqtt
// See https://github.com/openhab/openhab1-addons/tree/master/bundles/binding/org.openhab.binding.km200
public final class KM200 {

    private final KM200Device device;
    private final KM200Comm comm;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final Duration timeout;

    private static void assertNotBlank(String var, String message) {
        if (requireNonNull(var).isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    public KM200(String host, Duration timeout, String gatewayPassword, String privatePassword, String salt)
            throws KM200Exception, IOException, InterruptedException {

        assertNotBlank(host, "host must not be blank");
        requireNonNull(timeout);
        assertNotBlank(gatewayPassword, "gatewayPassword must not be blank");
        assertNotBlank(privatePassword, "privatePassword must not be blank");
        assertNotBlank(salt, "salt must not be blank");

        var device = new KM200Device();
        device.setCharSet("UTF-8");
        device.setGatewayPassword(gatewayPassword.replace("-", ""));
        device.setPrivatePassword(privatePassword);
        device.setIP4Address(host);
        device.setMD5Salt(salt);
        device.setInited(true);
        this.device = device;

        this.comm = new KM200Comm();
        this.timeout = timeout;
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
        if (response.statusCode() != 200) {
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

    private static final String USER_AGENT = "TeleHeater/2.2.3";

    private HttpRequest.Builder request(String path) {
        var uri = URI.create("http://" + device.getIP4Address() + path);
        return HttpRequest.newBuilder(uri) //
                .setHeader("User-Agent", USER_AGENT) //
                .setHeader("Accept", "application/json") //
                .timeout(timeout);
    }
}
