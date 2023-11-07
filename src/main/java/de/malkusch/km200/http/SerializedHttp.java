package de.malkusch.km200.http;

import java.io.IOException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.malkusch.km200.KM200Exception;

public final class SerializedHttp extends Http {

    private final Http http;
    private final Lock lock = new ReentrantLock();

    public SerializedHttp(Http http) {
        this.http = http;
    }

    @Override
    public Response exchange(Request request) throws IOException, InterruptedException, KM200Exception {
        lock.lockInterruptibly();
        try {
            return http.exchange(request);

        } finally {
            lock.unlock();
        }
    }
}
