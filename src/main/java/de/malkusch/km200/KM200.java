package de.malkusch.km200;

import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
import java.time.Duration;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.params.HttpClientParams;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Value;

// See https://github.com/hlipka/buderus2mqtt
// See https://github.com/openhab/openhab1-addons/tree/master/bundles/binding/org.openhab.binding.km200
public final class KM200 {

    private final KM200Device device;
    private final KM200Comm comm;
    private final ObjectMapper mapper = new ObjectMapper();

    private static void assertNotBlank(String var, String message) {
        if (requireNonNull(var).isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    public KM200(String host, Duration timeout, String gatewayPassword, String privatePassword, String salt) {

        assertNotBlank(host, "host must not be blank");
        requireNonNull(timeout);
        assertNotBlank(gatewayPassword, "gatewayPassword must not be blank");
        assertNotBlank(privatePassword, "privatePassword must not be blank");
        assertNotBlank(salt, "salt must not be blank");

        var device = new KM200Device();
        // device.setCharSet("UTF-8");
        device.setGatewayPassword(gatewayPassword.replace("-", ""));
        device.setPrivatePassword(privatePassword);
        device.setIP4Address(host);
        device.setMD5Salt(salt);
        device.setInited(true);
        this.device = device;

        var httpParams = new HttpClientParams();
        httpParams.setConnectionManagerTimeout(timeout.toMillis());
        httpParams.setSoTimeout((int) timeout.toMillis());
        var http = new HttpClient(httpParams);
        var comm = new KM200Comm(http);
        comm.getDataFromService(device, "/system");
        this.comm = comm;
    }

    @Value
    private static class UpdateString {
        public final String value;
    }

    public void update(String path, String value) throws KM200Exception {
        var update = new UpdateString(value);
        update(path, update);
    }

    @Value
    private static class UpdateFloat {
        public final BigDecimal value;
    }

    public void update(String path, int value) throws KM200Exception {
        update(path, new BigDecimal(value));
    }

    public void update(String path, BigDecimal value) throws KM200Exception {
        var update = new UpdateFloat(value);
        update(path, update);
    }

    private void update(String path, Object update) throws KM200Exception {
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
        var response = comm.sendDataToService(device, path, encrypted);
        if (!(response >= 200 && response < 300)) {
            throw new KM200Exception(String.format("Failed to update %s [%d]", path, response));
        }
    }

    public String query(String path) throws KM200Exception {
        var encrypted = comm.getDataFromService(device, path);
        if (encrypted == null) {
            throw new KM200Exception("No response when querying " + path);
        }
        var decrypted = comm.decodeMessage(device, encrypted);
        if (decrypted == null) {
            throw new KM200Exception("Could not decrypt query " + path);
        }
        return decrypted;
    }

    private JsonNode queryJson(String path) {
        try {
            return mapper.readTree(query(path));
        } catch (JsonProcessingException e) {
            throw new KM200Exception("Could not parse JSON from query " + path, e);
        }
    }

    public double queryDouble(String path) throws KM200Exception {
        var json = queryJson(path);
        return json.get("value").asDouble();
    }
}
