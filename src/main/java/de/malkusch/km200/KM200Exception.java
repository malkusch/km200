package de.malkusch.km200;

public class KM200Exception extends RuntimeException {
    private static final long serialVersionUID = -3012972913411313232L;

    public KM200Exception(String message) {
        super(message);
    }

    public KM200Exception(String message, Throwable cause) {
        super(message, cause);
    }

    public static class NotFound extends KM200Exception {
        private static final long serialVersionUID = -6497781961251724723L;

        public NotFound(String message) {
            super(message);
        }
    }

    public static class ServerError extends KM200Exception {
        private static final long serialVersionUID = -6497781961251724723L;

        public ServerError(String message) {
            super(message);
        }
    }

    public static class BadRequest extends KM200Exception {
        private static final long serialVersionUID = -6497781961251724723L;

        public BadRequest(String message) {
            super(message);
        }
    }

    public static class Forbidden extends KM200Exception {
        private static final long serialVersionUID = -6497781961251724723L;

        public Forbidden(String message) {
            super(message);
        }
    }

    public static class Locked extends KM200Exception {
        private static final long serialVersionUID = -6497781961251724723L;

        public Locked(String message) {
            super(message);
        }
    }
}
