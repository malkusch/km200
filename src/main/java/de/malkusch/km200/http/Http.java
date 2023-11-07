package de.malkusch.km200.http;

import java.io.IOException;

import de.malkusch.km200.KM200Exception;

public abstract class Http {

    sealed interface Request {

        String path();

        static record Get(String path) implements Request {

            @Override
            public String toString() {
                return "GET " + path;
            }
        }

        static record Post(String path, byte[] body) implements Request {

            @Override
            public String toString() {
                return "POST " + path;
            }
        }
    }

    public static record Response(int status, byte[] body) {
    }

    public final Response get(String path) throws KM200Exception, IOException, InterruptedException {
        return exchange(new Request.Get(path));
    }

    public final Response post(String path, byte[] body) throws KM200Exception, IOException, InterruptedException {
        return exchange(new Request.Post(path, body));
    }

    protected abstract Response exchange(Request request) throws IOException, InterruptedException, KM200Exception;

    static Response assertHttpOk(Request request, Response response) throws KM200Exception {
        var status = response.status();
        if (status >= 200 && status <= 299) {
            return response;

        } else {
            throw switch (status) {
            case 400 -> new KM200Exception.BadRequest(request + " was a bad request");
            case 403 -> new KM200Exception.Forbidden(request + " is forbidden");
            case 404 -> new KM200Exception.NotFound(request + " was not found");
            case 423 -> new KM200Exception.Locked(request + " was locked");
            case 500 -> new KM200Exception.ServerError(request + " resulted in a server error");
            default -> new KM200Exception(request + " failed with response code " + status);
            };
        }
    }
}
