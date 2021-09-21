package de.malkusch.km200;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import de.malkusch.km200.KM200Exception.Forbidden;
import de.malkusch.km200.KM200Tree.Node;

public record KM200Tree(List<Node> roots) {

    public Stream<Node> traverse() {
        return roots.stream().flatMap(KM200Tree::traverse);
    }

    private static Stream<Node> traverse(Node node) {
        return switch (node.type) {
        case "RefEnum" -> ((RefEnum) node).children.stream().flatMap(KM200Tree::traverse);
        default -> Stream.of(node);
        };
    }

    public static abstract class Node {
        private final String path;
        private final String type;

        Node(String path, String type) {
            this.path = path;
            this.type = type;
        }

        @Override
        public String toString() {
            return String.format("%s [%s]", path, type);
        }
    }

    public static class RefEnum extends Node {
        private final List<Node> children;

        RefEnum(String path, List<Node> children) {
            super(path, "RefEnum");
            this.children = children;
        }
    }

    public static class Value extends Node {
        private final String body;
        private final boolean writeable;
        private final boolean recordable;
        private final String value;

        Value(String path, String type, String value, boolean writeable, boolean recordable, String body) {
            super(path, type);
            this.body = body;
            this.value = value;
            this.writeable = writeable;
            this.recordable = recordable;
        }

        @Override
        public String toString() {
            var writeable = this.writeable ? "w" : "";
            var recordable = this.recordable ? "r" : "";
            var flags = writeable + recordable;
            return String.format("%s[%s]: %s", super.toString(), flags, value);
        }
    }

    public static class ForbiddenNode extends Node {
        ForbiddenNode(String path) {
            super(path, "Forbidden");
        }
    }

    static record Factory(KM200 km200, ObjectMapper mapper) {

        private static String[] WELL_KNOWN_ROOTS = { "/system", "/dhwCircuits", "/gateway", "/heatingCircuits",
                "/heatSources", "/notifications", "/recordings", "/solarCircuits" };

        public KM200Tree build() throws KM200Exception, IOException, InterruptedException {
            var roots = new ArrayList<Node>();
            for (var path : WELL_KNOWN_ROOTS) {
                roots.add(traverse(path));
            }
            return new KM200Tree(roots);
        }

        private Node traverse(String path) throws InterruptedException, KM200Exception, IOException {
            if (Thread.interrupted()) {
                throw new InterruptedException("Exploring was interrupted");
            }
            try {
                var response = km200.query(path);
                if (path.equals("/gateway/firmware")) {
                    return new Value(path, "firmware", "firmware", false, false, "firmware");
                }
                var json = mapper.readTree(response);
                var type = json.path("type").asText();
                switch (type) {
                case "stringValue":
                case "systeminfo":
                case "floatValue":
                case "arrayData":
                case "switchProgram":
                case "errorList":
                case "yRecording":
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

                    return new Value(path, type, value, writeable, recordable, json.toString());

                case "refEnum":
                    var children = new ArrayList<Node>();
                    for (var childJson : json.get("references")) {
                        children.add(traverse(childJson.get("id").asText()));
                    }
                    return new RefEnum(path, children);

                default:
                    throw new IllegalStateException("Unknown type " + type + ": " + json);
                }
            } catch (Forbidden e) {
                return new ForbiddenNode(path);
            }
        }
    }
}
