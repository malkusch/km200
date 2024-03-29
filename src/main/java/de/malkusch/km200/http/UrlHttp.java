package de.malkusch.km200.http;

import static de.malkusch.km200.http.Http.Response.successfullResponse;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;

import de.malkusch.km200.KM200Exception;
import de.malkusch.km200.http.Http.Request.Post;

public final class UrlHttp extends Http {

    private final String uri;
    private final String userAgent;
    private final int timeoutMillis;

    /**
     * Avoid undesired POST retries from UrlConnection
     */
    static {
        System.setProperty("sun.net.http.retryPost", "false");
    }

    public UrlHttp(String uri, String userAgent, Duration timeout) {
        this.uri = uri;
        this.userAgent = userAgent;
        this.timeoutMillis = (int) timeout.toMillis();
    }

    @Override
    protected Response exchange(Request request) throws IOException, InterruptedException, KM200Exception {
        var connection = connect(request);
        try {
            if (request instanceof Post post) {
                try (var output = connection.getOutputStream()) {
                    output.write(post.body());
                }
            }

            var status = connection.getResponseCode();
            if (status == -1) {
                throw new IOException(request + " received invalid HTTP response");
            }

            var input = switch (connection.getErrorStream()) {
            case InputStream error -> error;
            case null -> connection.getInputStream();
            };
            try (input) {
                var body = input.readAllBytes();
                return successfullResponse(request, status, body);
            }

        } catch (SocketTimeoutException e) {
            throw new HttpTimeoutException(request + " timed out");

        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connect(Request request) throws IOException {
        var uri = this.uri + request.path();
        try {
            if (!(new URI(uri).toURL().openConnection() instanceof HttpURLConnection connection)) {
                throw new IllegalStateException(uri + " is not a http url");
            }

            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("User-Agent", userAgent);

            if (request instanceof Post) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Accept", "application/json");
            }

            connection.connect();
            return connection;

        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Invalid uri " + uri, e);
        }
    }
}
