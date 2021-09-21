package de.malkusch.km200;

public class KM200Exception extends RuntimeException {
    private static final long serialVersionUID = -3012972913411313232L;

    KM200Exception(String message) {
        super(message);
    }

    KM200Exception(String message, Throwable cause) {
        super(message, cause);
    }

    public static class NotFound extends KM200Exception {
        private static final long serialVersionUID = -6497781961251724723L;

        NotFound(String message) {
            super(message);
        }
    }
    
    public static class Forbidden extends KM200Exception {
        private static final long serialVersionUID = -6497781961251724723L;
        
        Forbidden(String message) {
            super(message);
        }
    }
}
