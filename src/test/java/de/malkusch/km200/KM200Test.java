package de.malkusch.km200;

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
import static org.apache.commons.io.IOUtils.resourceToString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;

/**
 * To run these tests please provide the environment variables GATEWAY_PASSWORD,
 * PRIVATE_PASSWORD and SALT.
 */
@WireMockTest(httpPort = KM200Test.PORT)
public class KM200Test {

    private static final String GATEWAY_PASSWORD = System.getenv("GATEWAY_PASSWORD");
    private static final String PRIVATE_PASSWORD = System.getenv("PRIVATE_PASSWORD");
    private static final String SALT = System.getenv("SALT");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    static final int PORT = 8080;
    private static final String URI = "http://localhost:" + PORT;

    @BeforeEach
    public void stubSystem() throws Exception {
        stubFor(get("/system").willReturn(ok(loadBody("system"))));
    }

    @ParameterizedTest
    @ValueSource(strings = { "http://localhost:" + PORT, "http://localhost:" + PORT + "/" })
    public void shouldReplaceSlashesInURI(String uri) throws Exception {
        stubFor(get("/gateway/DateTime").willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(uri, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        km200.queryString("/gateway/DateTime");

        verify(getRequestedFor(urlEqualTo("/gateway/DateTime")));
    }

    @Test
    public void shouldSendUserAgent() throws Exception {
        stubFor(get("/gateway/DateTime").willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        km200.queryString("/gateway/DateTime");

        verify(getRequestedFor(anyUrl()).withHeader("User-Agent", equalTo(USER_AGENT)));
    }

    @Test
    public void shouldDecryptQuery() throws Exception {
        stubFor(get("/gateway/DateTime").willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        var dateTime = km200.queryString("/gateway/DateTime");

        assertEquals("2021-09-21T10:49:25", dateTime);
    }

    @Test
    public void shouldEncryptUpdate() throws Exception {
        stubFor(post("/gateway/DateTime").willReturn(ok()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        km200.update("/gateway/DateTime", LocalDateTime.parse("2021-09-21T10:49:25"));

        verify(postRequestedFor(urlEqualTo("/gateway/DateTime"))
                .withRequestBody(equalTo("jAsP208ITkn75xLI/WdARyIzbymlclVS92SvScEVkh4=")));
    }

    @Test
    public void shouldFailOnNonExistingPath() throws Exception {
        stubFor(get("/non-existing").willReturn(notFound()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.NotFound.class, () -> km200.queryString("/non-existing"));
    }

    @Test
    public void shouldFailOnForbiddenPath() throws Exception {
        stubFor(get("/forbidden").willReturn(status(403)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.Forbidden.class, () -> km200.queryString("/forbidden"));
    }

    @Test
    public void shouldFailOnServerError() throws Exception {
        stubFor(get("/server-error").willReturn(serverError()));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.class, () -> km200.queryString("/server-error"));
    }

    @Test
    public void shouldFailOnLocked() throws Exception {
        stubFor(post("/locked").willReturn(status(423)));
        var km200 = new KM200(URI, TIMEOUT, GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(KM200Exception.Locked.class, () -> km200.update("/locked", 42));
    }

    @Test
    public void shouldTimeout() throws Exception {
        stubFor(get("/timeout").willReturn(ok(loadBody("gateway.DateTime")).withFixedDelay(100)));
        var km200 = new KM200(URI, Duration.ofMillis(50), GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        assertThrows(HttpTimeoutException.class, () -> km200.query("/timeout"));
    }

    @Test
    public void shouldRetryOnTimeout() throws Exception {
        stubFor(get("/retry").inScenario("retry").whenScenarioStateIs(STARTED)
                .willReturn(notFound().withFixedDelay(100)).willSetStateTo("retry"));
        stubFor(get("/retry").inScenario("retry").whenScenarioStateIs("retry")
                .willReturn(ok(loadBody("gateway.DateTime"))));
        var km200 = new KM200(URI, Duration.ofMillis(50), GATEWAY_PASSWORD, PRIVATE_PASSWORD, SALT);

        var dateTime = km200.queryString("/retry");

        assertEquals("2021-09-21T10:49:25", dateTime);
    }

    private static String loadBody(String path) throws IOException {
        return resourceToString(path, UTF_8, KM200Test.class.getClassLoader());
    }
}
