package de.malkusch.km200.http;

import java.io.IOException;

import de.malkusch.km200.KM200Exception;

public abstract class Http {

    sealed interface Request {

        String path();

        static record Get(String path) implements Request {
        }

        static record Post(String path, byte[] body) implements Request {
        }
    }

    public static record Response(int status, byte[] body) {
    }

    protected abstract Response send(Request request) throws IOException, InterruptedException, KM200Exception;

    public final Response get(String path) throws KM200Exception, IOException, InterruptedException {
        return send(new Request.Get(path));
    }

    public final Response post(String path, byte[] body) throws KM200Exception, IOException, InterruptedException {
        return send(new Request.Post(path, body));
    }
}
