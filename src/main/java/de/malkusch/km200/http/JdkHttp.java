package de.malkusch.km200.http;

import static java.net.http.HttpClient.newBuilder;
import static java.net.http.HttpClient.Redirect.ALWAYS;
import static java.net.http.HttpClient.Version.HTTP_1_1;
import static java.net.http.HttpRequest.BodyPublishers.ofByteArray;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import de.malkusch.km200.KM200Exception;
import de.malkusch.km200.http.Http.Request.Get;
import de.malkusch.km200.http.Http.Request.Post;

public final class JdkHttp extends Http {

    private final HttpClient client;
    private final String uri;
    private final String userAgent;
    private final Duration timeout;
    private final Duration hardTimeout;
    private final ExecutorService executor;

    public JdkHttp(String uri, String userAgent, Duration timeout) {
        this.uri = uri;
        this.userAgent = userAgent;
        this.timeout = timeout;
        this.hardTimeout = timeout.multipliedBy(2);

        this.executor = Executors.newCachedThreadPool(r -> {
            var thread = new Thread(r, "KM200 Http");
            thread.setDaemon(true);
            return thread;
        });

        this.client = newBuilder() //
                .connectTimeout(timeout) //
                .cookieHandler(new CookieManager()) //
                .followRedirects(ALWAYS) //
                .executor(executor) //
                .version(HTTP_1_1) //
                .build();
    }

    @Override
    public Response exchange(Request request) throws IOException, InterruptedException, KM200Exception {
        var httpRequest = httpRequest(request);
        var response = send(httpRequest);
        return assertHttpOk(request, response);
    }

    private Response send(HttpRequest request) throws IOException, InterruptedException {
        var response = client.send(request, BodyHandlers.ofByteArray());
        return new Response(response.statusCode(), response.body());
    }

    private Response send2(HttpRequest request) throws IOException, InterruptedException {
        try {
            /*
             * It appears that the JDK's http client has a bug which causes it
             * to block infinitely. This is the async workaround with the hard
             * timeout of CompletableFuture.get().
             * 
             * https://bugs.openjdk.org/browse/JDK-8258397
             * https://bugs.openjdk.org/browse/JDK-8208693
             * https://bugs.openjdk.org/browse/JDK-8254223
             */
            return client //
                    .sendAsync(request, BodyHandlers.ofByteArray()) //
                    .thenApply(it -> new Response(it.statusCode(), it.body())) //
                    .get(hardTimeout.toMillis(), MILLISECONDS);

        } catch (TimeoutException e) {
            throw new HttpTimeoutException(request.uri() + " timed out: " + e.getMessage());

        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException cause) {
                throw cause;

            } else if (e.getCause() instanceof InterruptedException cause) {
                throw cause;

            } else if (e.getCause() instanceof KM200Exception cause) {
                throw cause;

            } else {
                throw new KM200Exception("Unexpected error for " + request.uri(), e);
            }
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

    /*
     * @Override public void close() throws Exception { executor.shutdown(); if
     * (executor.awaitTermination(timeout.toMillis(), MILLISECONDS)) { return; }
     * executor.shutdownNow(); if
     * (!executor.awaitTermination(timeout.toMillis(), MILLISECONDS)) { throw
     * new IOException("Couldn't shutdown executor"); } }
     */
}
