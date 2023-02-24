package de.malkusch.km200;

import static java.lang.Thread.currentThread;
import static java.util.Arrays.stream;

import java.io.IOException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.malkusch.km200.KM200Exception.Forbidden;

public abstract class KM200Endpoint {
    private final String path;
    private final String type;

    KM200Endpoint(String path, String type) {
        this.path = path;
        this.type = type;
    }

    public String path() {
        return path;
    }

    @Override
    public String toString() {
        return String.format("%s [%s]", path, type);
    }

    public static class Value extends KM200Endpoint {
        private final String body;
        private final boolean writeable;
        private final boolean recordable;
        private final String value;
        private final String allowedValues;

        Value(String path, String type, String value, String allowedValues, boolean writeable, boolean recordable,
                String body) {
            super(path, type);
            this.body = body;
            this.value = value;
            this.allowedValues = allowedValues;
            this.writeable = writeable;
            this.recordable = recordable;
        }

        @Override
        public String toString() {
            var writeable = this.writeable ? "w" : "";
            var recordable = this.recordable ? "r" : "";
            var flags = writeable + recordable;
            var allowed = allowedValues != null ? allowedValues : "";
            return String.format("%s[%s]: %s %s", super.toString(), flags, value, allowed);
        }
    }

    public static class ForbiddenNode extends KM200Endpoint {
        ForbiddenNode(String path) {
            super(path, "Forbidden");
        }
    }

    public static class UnknownNode extends KM200Endpoint {
        private final String value;

        UnknownNode(String path, String type, String value) {
            super(path, type);
            this.value = value;
        }

        @Override
        public String toString() {
            return String.format("%s [UNKNOWN]: %s", super.toString(), value);
        }
    }

    static record Factory(KM200 km200, ObjectMapper mapper) {

        private static final String[] WELL_KNOWN_ROOTS = { //
                "/system", //
                "/dhwCircuits", //
                "/gateway", //
                "/heatingCircuits", //
                "/heatSources", //
                "/notifications", //
                "/recordings", //
                "/solarCircuits" //
        };

        public Stream<KM200Endpoint> build() {
            return stream(WELL_KNOWN_ROOTS) //
                    .flatMap(this::traverse);
        }

        private static final Value FIRMWARE = new Value("/gateway/firmware", "firmware", "firmware", null, false, false,
                "firmware");

        private Stream<KM200Endpoint> traverse(String path) throws KM200Exception {
            if (path.equals(FIRMWARE.path())) {
                return Stream.of(FIRMWARE);
            }

            try {
                var response = km200.query(path);

                var json = mapper.readTree(response);
                var type = json.path("type").asText();

                return switch (type) {

                case "stringValue", "systeminfo", "floatValue", //
                        "arrayData", "switchProgram", //
                        "errorList", "yRecording" -> Stream.of(value(path, type, json));

                case "refEnum" -> StreamSupport //
                        .stream(json.get("references").spliterator(), false) //
                        .map(it -> it.get("id").asText()) //
                        .flatMap(this::traverse);

                default -> Stream.of(new UnknownNode(path, type, json.toString()));
                };

            } catch (Forbidden e) {
                return Stream.of(new ForbiddenNode(path));

            } catch (IOException e) {
                throw new KM200Exception("Traversing " + path + " failed", e);

            } catch (InterruptedException e) {
                currentThread().interrupt();
                return Stream.empty();
            }
        }

        private static Value value(String path, String type, JsonNode json) {
            var writeable = json.path("writeable").asBoolean(false);
            var recordable = json.path("recordable").asBoolean(false);

            String value;
            if (json.has("value")) {
                value = json.get("value").asText();
            } else if (json.has("values")) {
                value = json.get("values").toString();
            } else {
                value = json.toString();
            }

            String allowedValues = null;
            if (json.has("allowedValues")) {
                allowedValues = json.get("allowedValues").toString();
            }

            return new Value(path, type, value, allowedValues, writeable, recordable, json.toString());
        }
    }
}
