package de.malkusch.km200.http;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.http.HttpClient;
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
     * 
     * {@link HttpClient}
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

            try (var input = connection.getErrorStream() != null ? connection.getErrorStream()
                    : connection.getInputStream()) {

                var body = input.readAllBytes();
                var response = new Response(status, body);
                return assertHttpOk(request, response);
            }

        } catch (SocketTimeoutException e) {
            throw new HttpTimeoutException(request + " timed out");

        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connect(Request request) throws IOException {
        try {
            var connection = (HttpURLConnection) new URL(uri + request.path()).openConnection();
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestProperty("User-Agent", userAgent);

            if (request instanceof Post) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Accept", "application/json");
            }

            connection.connect();

            return connection;

        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid path " + request, e);
        }
    }
}
