package de.malkusch.km200;

public class KM200Exception extends RuntimeException {
    private static final long serialVersionUID = -3012972913411313232L;

    public KM200Exception(String message) {
        super(message);
    }

    public KM200Exception(String message, Throwable cause) {
        super(message, cause);
    }
}
