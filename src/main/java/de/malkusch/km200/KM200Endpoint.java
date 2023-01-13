package de.malkusch.km200;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.malkusch.km200.KM200Exception.Forbidden;

public abstract class KM200Endpoint {
    private final String path;
    private final String type;

    KM200Endpoint(String path, String type) {
        this.path = path;
        this.type = type;
    }

    @Override
    public String toString() {
        return String.format("%s [%s]", path, type);
    }

    public static class RefEnum extends KM200Endpoint {
        private final List<KM200Endpoint> children;

        RefEnum(String path, List<KM200Endpoint> children) {
            super(path, "RefEnum");
            this.children = children;
        }
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

        public Stream<KM200Endpoint> build() throws KM200Exception, IOException, InterruptedException {
            var roots = new ArrayList<KM200Endpoint>();
            for (var path : WELL_KNOWN_ROOTS) {
                roots.add(traverse(path));
            }
            return roots.stream().flatMap(Factory::traverse);
        }

        private static Stream<KM200Endpoint> traverse(KM200Endpoint node) {
            return switch (node.type) {
            case "RefEnum" -> ((RefEnum) node).children.stream().flatMap(Factory::traverse);
            default -> Stream.of(node);
            };
        }

        private KM200Endpoint traverse(String path) throws InterruptedException, KM200Exception, IOException {
            if (Thread.interrupted()) {
                throw new InterruptedException("Exploring was interrupted");
            }
            try {
                var response = km200.query(path);
                if (path.equals("/gateway/firmware")) {
                    return new Value(path, "firmware", "firmware", null, false, false, "firmware");
                }
                var json = mapper.readTree(response);
                var type = json.path("type").asText();

                return switch (type) {
                case "stringValue", "systeminfo", "floatValue", "arrayData", "switchProgram", "errorList", "yRecording" -> {
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

                    yield new Value(path, type, value, allowedValues, writeable, recordable, json.toString());
                }

                case "refEnum" -> {
                    var children = new ArrayList<KM200Endpoint>();
                    for (var childJson : json.get("references")) {
                        children.add(traverse(childJson.get("id").asText()));
                    }
                    yield new RefEnum(path, children);
                }

                default -> new UnknownNode(path, type, json.toString());
                };
            } catch (Forbidden e) {
                return new ForbiddenNode(path);
            }
        }
    }
}
