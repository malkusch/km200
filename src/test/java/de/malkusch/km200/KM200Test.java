package de.malkusch.km200;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static de.malkusch.km200.KM200.USER_AGENT;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.io.IOUtils.resourceToString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;

import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

@WireMockTest(httpPort = KM200Test.PORT)
public class KM200Test {

    private static final String GATEWAY_PASSWORD = "aaaa-bbbb-cccc-dddd";
    private static final String PRIVATE_PASSWORD = "secret1";
    private static final String SALT = "abababababababababababababababababababababababababababababababab";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int PORT = 8080;
    private static final String URI = "http://localhost:" + PORT;

    @BeforeEach
    public void stubSystem() throws Exception {
        stubFor(get("/system").willReturn(ok(loadBody("system"))));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://localhost:" + PORT, "http://localhost:" + PORT + "/" })
    public void queryShouldReplaceSlashesInURI(String uri) throws Exception {
        stubFor(get("/gateway/DateTime").willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(uri, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        km200.queryString("/gateway/DateTime");

        verify(getRequestedFor(urlEqualTo("/gateway/DateTime")));
    }

    @Test
    public void queryShouldSendUserAgent() throws Exception {
        stubFor(get("/gateway/DateTime").willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        km200.queryString("/gateway/DateTime");

        verify(getRequestedFor(anyUrl()).withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @Test
    public void updateShouldSendUserAgent() throws Exception {
        stubFor(post("/update-headers").willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        km200.update("/update-headers", 42);

        verify(postRequestedFor(anyUrl()).withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @Test
    public void queryShouldDecryptQuery() throws Exception {
        stubFor(get("/gateway/DateTime").willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        var dateTime = km200.queryString("/gateway/DateTime");

        assertEquals("2021-09-21T10:49:25", dateTime);
    }

    @Test
    public void updateShouldEncrypt() throws Exception {
        stubFor(post("/gateway/DateTime").willReturn(ok()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        km200.update("/gateway/DateTime", LocalDateTime.parse("2021-09-21T10:49:25"));

        verify(postRequestedFor(urlEqualTo("/gateway/DateTime"))
                .withRequestBody(equalTo("5xIVJSMa037r4XkbMhFnkgKrnu4nsjb9+oeBkEwVIj8=")));
    }

    @Test
    public void queryShouldFailOnNonExistingPath() throws Exception {
        stubFor(get("/non-existing").willReturn(notFound()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.NotFound.class, () -> km200.queryString("/non-existing"));
    }

    @Test
    public void updateShouldFailOnNonExistingPath() throws Exception {
        stubFor(post("/update-non-existing").willReturn(notFound()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.NotFound.class, () -> km200.update("/update-non-existing", 42));
    }

    @Test
    public void queryShouldFailOnForbiddenPath() throws Exception {
        stubFor(get("/forbidden").willReturn(status(403)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.Forbidden.class, () -> km200.queryString("/forbidden"));
    }

    @Test
    public void updateShouldFailOnForbiddenPath() throws Exception {
        stubFor(post("/update-forbidden").willReturn(status(403)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.Forbidden.class, () -> km200.update("/update-forbidden", 42));
    }

    @Test
    public void queryShouldFailOnServerError() throws Exception {
        stubFor(get("/server-error").willReturn(serverError()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.ServerError.class, () -> km200.queryString("/server-error"));
    }

    @Test
    public void updateShouldFailOnServerError() throws Exception {
        stubFor(post("/update-server-error").willReturn(serverError()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.ServerError.class, () -> km200.update("/update-server-error", 42));
    }

    @Test
    public void queryShouldFailOnUnknownError() throws Exception {
        stubFor(get("/unknown-error").willReturn(status(599)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.class, () -> km200.queryString("/unknown-error"));
    }

    @Test
    public void updateShouldFailOnUnknownError() throws Exception {
        stubFor(post("/update-unknown-error").willReturn(status(599)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.class, () -> km200.update("/update-unknown-error", 42));
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    public void queryShouldFailOnBadResponse(Fault fault) throws Exception {
        stubFor(get("/bad-response").willReturn(aResponse().withFault(fault)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(IOException.class, () -> km200.queryString("/bad-response"));
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    public void updateShouldFailOnBadResponse(Fault fault) throws Exception {
        stubFor(post("/update-bad-response").willReturn(aResponse().withFault(fault)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(IOException.class, () -> km200.update("/update-bad-response", 42));
    }

    @Test
    public void updateShouldFailOnLocked() throws Exception {
        stubFor(post("/locked").willReturn(status(423)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.Locked.class, () -> km200.update("/locked", 42));
    }

    @Test
    public void queryShouldTimeout() throws Exception {
        stubFor(get("/timeout").willReturn(ok(loadBody("gateway.DateTime")).withFixedDelay(100)));
        var km200 = new KM200(URI, Duration.ofMillis(50), GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(HttpTimeoutException.class, () -> km200.query("/timeout"));
    }

    @Test
    public void updateShouldTimeout() throws Exception {
        stubFor(post("/update-timeout").willReturn(ok(loadBody("gateway.DateTime")).withFixedDelay(100)));
        var km200 = new KM200(URI, Duration.ofMillis(50), GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(HttpTimeoutException.class, () -> km200.update("/update-timeout", 42));
    }

    @Test
    public void queryShouldTimeoutResponseBody() throws Exception {
        stubFor(get("/timeout-body").willReturn(ok(loadBody("gateway.DateTime")).withChunkedDribbleDelay(5, 20000)));
        var km200 = new KM200(URI, Duration.ofMillis(50), GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(HttpTimeoutException.class, () -> km200.query("/timeout-body"));
    }

    @Test
    public void updateShouldTimeoutResponseBody() throws Exception {
        stubFor(post("/update-timeout-body")
                .willReturn(ok(loadBody("gateway.DateTime")).withChunkedDribbleDelay(5, 20000)));
        var km200 = new KM200(URI, Duration.ofMillis(50), GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(HttpTimeoutException.class, () -> km200.update("/update-timeout-body", 42));
    }

    @Test
    public void queryShouldRetryOnTimeout() throws Exception {
        stubFor(get("/retry").inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(notFound().withFixedDelay(100)).willSetStateTo("retry"));
        stubFor(get("/retry").inScenario("retry").whenScenarioStateIs("retry")
                .willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, Duration.ofMillis(50), GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        var dateTime = km200.queryString("/retry");

        assertEquals("2021-09-21T10:49:25", dateTime);
        verify(2, getRequestedFor(urlEqualTo("/retry")));
    }

    @Test
    public void queryShouldRetryOnServerError() throws Exception {
        stubFor(get("/retry500").inScenario("retry500").whenScenarioStateIs(STARTED).willReturn(serverError())
                .willSetStateTo("retry2"));
        stubFor(get("/retry500").inScenario("retry500").whenScenarioStateIs("retry2").willReturn(serverError())
                .willSetStateTo("retry3"));
        stubFor(get("/retry500").inScenario("retry500").whenScenarioStateIs("retry3").willReturn(serverError())
                .willSetStateTo("ok"));
        stubFor(get("/retry500").inScenario("retry500").whenScenarioStateIs("ok")
                .willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        var dateTime = km200.queryString("/retry500");

        assertEquals("2021-09-21T10:49:25", dateTime);
        verify(4, getRequestedFor(urlEqualTo("/retry500")));
    }

    @ParameterizedTest
    @EnumSource(Fault.class)
    public void queryShouldRetryOnBadResponse(Fault fault) throws Exception {
        stubFor(get("/retry-bad-response").inScenario("retryBadResponse").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withFault(fault)).willSetStateTo("retry2"));
        stubFor(get("/retry-bad-response").inScenario("retryBadResponse").whenScenarioStateIs("retry2")
                .willReturn(aResponse().withFault(fault)).willSetStateTo("retry3"));
        stubFor(get("/retry-bad-response").inScenario("retryBadResponse").whenScenarioStateIs("retry3")
                .willReturn(aResponse().withFault(fault)).willSetStateTo("ok"));
        stubFor(get("/retry-bad-response").inScenario("retryBadResponse").whenScenarioStateIs("ok")
                .willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        var dateTime = km200.queryString("/retry-bad-response");

        assertEquals("2021-09-21T10:49:25", dateTime);
        verify(4, getRequestedFor(urlEqualTo("/retry-bad-response")));
    }

    @Test
    public void queryShouldWaitWhenRetrying() throws Exception {
        stubFor(get("/retry-wait").inScenario("retry-wait").whenScenarioStateIs(STARTED).willReturn(serverError())
                .willSetStateTo("retry-wait2"));
        stubFor(get("/retry-wait").inScenario("retry-wait").whenScenarioStateIs("retry-wait2").willReturn(serverError())
                .willSetStateTo("retry-wait3"));
        stubFor(get("/retry-wait").inScenario("retry-wait").whenScenarioStateIs("retry-wait3").willReturn(serverError())
                .willSetStateTo("ok"));
        stubFor(get("/retry-wait").inScenario("retry-wait").whenScenarioStateIs("ok")
                .willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        var stopwatch = StopWatch.createStarted();
        var dateTime = km200.queryString("/retry-wait");
        stopwatch.stop();

        var seconds = stopwatch.getTime(MILLISECONDS) / 1000.0;
        assertTrue(seconds > 2, "The retry was to fast: " + seconds + " seconds");
        assertEquals("2021-09-21T10:49:25", dateTime);
        verify(4, getRequestedFor(urlEqualTo("/retry-wait")));
    }

    private static String loadBody(String path) throws IOException {
        return resourceToString(path, UTF_8, KM200Test.class.getClassLoader());
    }
}
