package de.malkusch.km200.http;

import static java.net.http.HttpClient.newBuilder;
import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

import de.malkusch.km200.KM200Exception;
import de.malkusch.km200.http.Http.Request.Get;
import de.malkusch.km200.http.Http.Request.Post;

public final class JdkHttp extends Http {

    private final HttpClient client;
    private final String uri;
    private final String userAgent;
    private final Duration timeout;

    public JdkHttp(String uri, String userAgent, Duration timeout) {
        this.uri = uri;
        this.userAgent = userAgent;
        this.timeout = timeout;

        this.client = newBuilder() //
                .connectTimeout(timeout) //
                .cookieHandler(new CookieManager()) //
                .followRedirects(ALWAYS) //
                .build();
    }

    @Override
    public Response send(Request request) throws IOException, InterruptedException, KM200Exception {
        var httpRequest = httpRequest(request);
        var response = client.send(httpRequest, BodyHandlers.ofByteArray());
        var status = response.statusCode();

        if (status >= 200 && status <= 299) {
            return new Response(status, response.body());

        } else {
            throw switch (status) {
            case 400 -> new KM200Exception.BadRequest("Bad request to " + request.path());
            case 403 -> new KM200Exception.Forbidden(request.path() + " is forbidden");
            case 404 -> new KM200Exception.NotFound(request.path() + " was not found");
            case 423 -> new KM200Exception.Locked(request.path() + " was locked");
            case 500 -> new KM200Exception.ServerError(request.path() + " resulted in a server error");
            default -> new KM200Exception(request.path() + " failed with response code " + status);
            };
        }
    }

    private HttpRequest httpRequest(Request request) {
        var builder = HttpRequest.newBuilder(URI.create(uri + request.path())) //
                .setHeader("User-Agent", userAgent) //
                .setHeader("Accept", "application/json") //
                .timeout(timeout);

        if (request instanceof Get) {
            builder.GET();

        } else if (request instanceof Post post) {
            builder.POST(ofByteArray(post.body()));

        } else {
            throw new IllegalStateException();
        }

        return builder.build();
    }
}
