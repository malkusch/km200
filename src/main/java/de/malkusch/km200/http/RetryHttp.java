package de.malkusch.km200.http;

import java.io.IOException;
import java.time.Duration;

import de.malkusch.km200.KM200Exception;
import dev.failsafe.Failsafe;
import dev.failsafe.FailsafeException;
import dev.failsafe.FailsafeExecutor;
import dev.failsafe.RetryPolicy;

public final class RetryHttp extends Http {

    private static final Duration RETRY_DELAY_MIN = Duration.ofSeconds(1);
    private static final Duration RETRY_DELAY_MAX = Duration.ofSeconds(2);

    private final Http http;
    private final FailsafeExecutor<Response> retry;

    @SafeVarargs
    public RetryHttp(Http http, int retries, Class<? extends Throwable>... exceptions) {
        this.http = http;
        this.retry = Failsafe.with( //
                RetryPolicy.<Response> builder() //
                        .handle(exceptions) //
                        .withMaxRetries(retries) //
                        .withDelay(RETRY_DELAY_MIN, RETRY_DELAY_MAX) //
                        .build());
    }

    @Override
    public Response exchange(Request request) throws IOException, InterruptedException, KM200Exception {
        try {
            return retry.get(() -> http.exchange(request));

        } catch (FailsafeException e) {
            switch (e.getCause()) {
            case IOException cause -> throw cause;
            case InterruptedException cause -> throw cause;
            case KM200Exception cause -> throw cause;
            case Throwable cause -> throw new KM200Exception("Unexpected retry error for " + request.path(), cause);
            case null -> throw new KM200Exception("Unexpected retry error for " + request.path(), e);
            }
        }
    }
}
