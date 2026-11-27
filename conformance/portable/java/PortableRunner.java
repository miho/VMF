package eu.mihosoft.vmf.portable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import vjavax.observer.Subscription;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the portable VMF scenarios (see conformance/portable/README.md) against the generated Java code.
 *
 * <p>Usage: {@code PortableRunner <scenarios-dir> <unsupported-models> <expected-failures> <results.json>}</p>
 */
public final class PortableRunner {

    static final String TARGET = "java";
    static final String BASE_PACKAGE = "eu.mihosoft.vmf.portable.";
    static final Set<String> CAPABILITIES = Set.of(
            "core", "views", "events", "history", "reflection", "equality-modes");

    private PortableRunner() {
    }

    public static void main(String[] args) throws IOException {
        Path scenarios = Paths.get(args[0]);
        Map<String, String> unsupported = readUnsupported(Paths.get(args[1]));
        Map<String, String> expectedFailures = readExpectedFailures(Paths.get(args[2]));
        Path resultsFile = Paths.get(args[3]);
        String filter = args.length > 4 ? args[4] : "";

        ObjectMapper mapper = new ObjectMapper();
        List<Path> files;
        try (Stream<Path> paths = Files.list(scenarios)) {
            files = paths.filter(path -> path.toString().endsWith(".json")).sorted().collect(Collectors.toList());
        }

        List<Result> results = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (Path file : files) {
            JsonNode root = mapper.readTree(file.toFile());
            String stem = file.getFileName().toString().replaceFirst("\\.json$", "");
            for (JsonNode scenario : root.path("scenarios")) {
                String id = stem + "/" + scenario.path("id").asText();
                if (!ids.add(id)) {
                    throw new IllegalStateException("Duplicate scenario id " + id);
                }
                if (!filter.isEmpty() && !id.contains(filter)) {
                    continue;
                }
                String model = scenario.has("model") ? scenario.get("model").asText() : root.path("model").asText();
                List<String> requires = new ArrayList<>();
                scenario.path("requires").forEach(node -> requires.add(node.asText()));
                String module = requires.isEmpty() ? "core" : String.join("+", requires);
                Result result = new Result(id, module);
                if (!CAPABILITIES.containsAll(requires)) {
                    result.status = "skipped";
                    result.message = "target does not claim " + requires;
                } else if (unsupported.containsKey(model)) {
                    result.status = "failed";
                    result.message = "model '" + model + "' was not generated: " + unsupported.get(model);
                } else {
                    try {
                        new Execution(model).run(scenario.path("steps"));
                        result.status = "passed";
                    } catch (Throwable error) {
                        result.status = "failed";
                        result.message = describe(error);
                    }
                }
                results.add(result);
            }
        }
        System.exit(report(results, expectedFailures, resultsFile, mapper) ? 0 : 1);
    }

    // ------------------------------------------------------------------------------------------------ reporting

    static final class Result {
        final String id;
        final String module;
        String status;
        String message = "";

        Result(String id, String module) {
            this.id = id;
            this.module = module;
        }
    }

    static boolean report(List<Result> results, Map<String, String> expectedFailures, Path file, ObjectMapper mapper)
            throws IOException {
        List<String> unexpectedFailures = new ArrayList<>();
        List<String> unexpectedPasses = new ArrayList<>();
        Map<String, int[]> modules = new TreeMap<>();
        int passed = 0;
        int failed = 0;
        int expected = 0;
        int skipped = 0;
        ArrayNode json = mapper.createArrayNode();
        for (Result result : results) {
            int[] counts = modules.computeIfAbsent(result.module, key -> new int[3]);
            boolean known = expectedFailures.containsKey(result.id);
            switch (result.status) {
                case "passed":
                    passed++;
                    counts[0]++;
                    if (known) {
                        unexpectedPasses.add(result.id);
                    }
                    break;
                case "failed":
                    failed++;
                    counts[1]++;
                    if (known) {
                        expected++;
                    } else {
                        unexpectedFailures.add(result.id + ": " + result.message);
                    }
                    break;
                default:
                    skipped++;
                    counts[2]++;
            }
            ObjectNode node = json.addObject();
            node.put("id", result.id);
            node.put("module", result.module);
            node.put("status", result.status);
            node.put("expectedFailure", known);
            node.put("message", result.message);
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        mapper.enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), json);

        for (Result result : results) {
            if ("failed".equals(result.status)) {
                System.out.println("  FAIL " + result.id + (expectedFailures.containsKey(result.id) ? " (expected)" : "")
                        + ": " + result.message);
            }
        }
        System.out.println();
        System.out.println(TARGET + " portable scenarios by module (passed/failed/skipped):");
        modules.forEach((module, counts) ->
                System.out.println("  " + module + ": " + counts[0] + "/" + counts[1] + "/" + counts[2]));
        int run = passed + failed;
        System.out.println(TARGET + ": " + passed + " of " + run + " passed, " + failed + " failed (" + expected
                + " expected), " + skipped + " skipped");
        for (String id : unexpectedPasses) {
            System.out.println("UNEXPECTED PASS (remove from expected failures): " + id);
        }
        for (String failure : unexpectedFailures) {
            System.out.println("UNEXPECTED FAILURE: " + failure);
        }
        return unexpectedFailures.isEmpty() && unexpectedPasses.isEmpty();
    }

    static Map<String, String> readUnsupported(Path file) throws IOException {
        Map<String, String> models = new HashMap<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0) {
                    models.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return models;
    }

    static Map<String, String> readExpectedFailures(Path file) throws IOException {
        Map<String, String> failures = new LinkedHashMap<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int comment = trimmed.indexOf('#');
                String id = (comment < 0 ? trimmed : trimmed.substring(0, comment)).trim();
                failures.put(id, comment < 0 ? "" : trimmed.substring(comment + 1).trim());
            }
        }
        return failures;
    }

    static String describe(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause instanceof ScenarioFailure ? cause.getMessage()
                : cause.getClass().getSimpleName() + ": " + cause.getMessage();
        return message == null ? "" : message.replaceAll("\\s+", " ");
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** A scenario expectation that did not hold. */
    static final class ScenarioFailure extends RuntimeException {
        ScenarioFailure(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------------------------------------ execution

    /** One recorded change notification, kept raw until a scenario compares it. */
    static final class Event {
        final int step;
        final Object source;
        final String property;
        final String kind;
        final Object oldValue;
        final Object newValue;
        final List<Object> elements;
        final List<Integer> indices;

        Event(int step, Object source, String property, String kind, Object oldValue, Object newValue,
              List<Object> elements, List<Integer> indices) {
            this.step = step;
            this.source = source;
            this.property = property;
            this.kind = kind;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.elements = elements;
            this.indices = indices;
        }
    }

    static final class Execution {
        static final List<String> OPERATIONS = List.of("new", "set", "add", "insert", "remove", "clear", "check",
                "let", "equal", "notEqual", "nativeEqual", "nativeNotEqual", "same", "notSame", "copy", "readOnly",
                "instanceOf", "notInstanceOf", "listen", "unlisten", "events", "record", "recorded", "undoAll",
                "redoAll", "properties", "annotations");

        final String model;
        final Map<String, Object> labels = new LinkedHashMap<>();
        final Map<String, List<Event>> events = new HashMap<>();
        final Map<String, Subscription> subscriptions = new HashMap<>();
        final Map<String, List<Change>> undone = new HashMap<>();
        int step;

        Execution(String model) {
            this.model = model;
        }

        void run(JsonNode steps) {
            for (JsonNode node : steps) {
                step++;
                String operation = operation(node);
                try {
                    if (node.path("fails").asBoolean(false)) {
                        expectRejection(operation, node);
                    } else {
                        execute(operation, node);
                    }
                } catch (ScenarioFailure failure) {
                    throw new ScenarioFailure("step " + step + " (" + operation + "): " + failure.getMessage());
                } catch (Throwable error) {
                    throw new ScenarioFailure("step " + step + " (" + operation + ") threw " + describe(error));
                }
            }
        }

        static String operation(JsonNode node) {
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (OPERATIONS.contains(name)) {
                    return name;
                }
            }
            throw new ScenarioFailure("unknown step " + node);
        }

        void expectRejection(String operation, JsonNode node) {
            String before = fingerprint();
            try {
                execute(operation, node);
            } catch (ScenarioFailure failure) {
                throw failure;
            } catch (Throwable expected) {
                String after = fingerprint();
                if (!before.equals(after)) {
                    throw new ScenarioFailure("the rejected edit changed the model:\n before " + before
                            + "\n after  " + after);
                }
                return;
            }
            throw new ScenarioFailure("expected the edit to be rejected, but it succeeded");
        }

        void execute(String operation, JsonNode node) throws Exception {
            switch (operation) {
                case "new":
                    labels.put(node.get("new").asText(), create(node.get("type").asText(), node.get("with")));
                    break;
                case "set":
                    set(node.get("set").asText(), node);
                    break;
                case "add":
                    add(node.get("add").asText(), node, -1);
                    break;
                case "insert":
                    add(node.get("insert").asText(), node, node.get("at").asInt());
                    break;
                case "remove":
                    remove(node.get("remove").asText(), node);
                    break;
                case "clear":
                    mutableList(node.get("clear").asText()).clear();
                    break;
                case "check":
                    check(node.get("check").asText(), node);
                    break;
                case "let":
                    labels.put(node.get("let").asText(), resolve(node.get("path").asText()));
                    break;
                case "equal":
                    pairs(node.get("equal"), (a, b) -> {
                        if (!content(a).equals(b) || !content(b).equals(a)) {
                            throw new ScenarioFailure("expected content equality");
                        }
                        if (content(a).hashCode() != content(b).hashCode()) {
                            throw new ScenarioFailure("content-equal objects have different content hash codes");
                        }
                    });
                    break;
                case "notEqual":
                    pairs(node.get("notEqual"), (a, b) -> {
                        if (content(a).equals(b) || content(b).equals(a)) {
                            throw new ScenarioFailure("expected content inequality");
                        }
                    });
                    break;
                case "nativeEqual":
                    pairs(node.get("nativeEqual"), (a, b) -> {
                        if (!a.equals(b) || !b.equals(a) || a.hashCode() != b.hashCode()) {
                            throw new ScenarioFailure("expected native equality with equal hash codes");
                        }
                    });
                    break;
                case "nativeNotEqual":
                    pairs(node.get("nativeNotEqual"), (a, b) -> {
                        if (a.equals(b) || b.equals(a)) {
                            throw new ScenarioFailure("expected native inequality");
                        }
                    });
                    break;
                case "same":
                    pairs(node.get("same"), (a, b) -> {
                        if (a != b) {
                            throw new ScenarioFailure("expected the same object");
                        }
                    });
                    break;
                case "notSame":
                    pairs(node.get("notSame"), (a, b) -> {
                        if (a == b) {
                            throw new ScenarioFailure("expected different objects");
                        }
                    });
                    break;
                case "copy":
                    labels.put(node.get("as").asText(), content(resolve(node.get("copy").asText())).deepCopy());
                    break;
                case "readOnly":
                    Object model = resolve(node.get("readOnly").asText());
                    Method asReadOnly = model.getClass().getMethod("asReadOnly");
                    asReadOnly.setAccessible(true);
                    labels.put(node.get("as").asText(), asReadOnly.invoke(model));
                    break;
                case "instanceOf":
                case "notInstanceOf":
                    boolean instance = modelClass(node.get("type").asText())
                            .isInstance(resolve(node.get(operation).asText()));
                    if (instance != operation.equals("instanceOf")) {
                        throw new ScenarioFailure("expected " + node.get(operation).asText()
                                + (instance ? " not " : " ") + "to be a " + node.get("type").asText());
                    }
                    break;
                case "listen":
                    listen(node.get("listen").asText(), node.get("as").asText(),
                            node.path("scope").asText("recursive"));
                    break;
                case "unlisten":
                    subscriptions.remove(node.get("unlisten").asText()).unsubscribe();
                    break;
                case "events":
                    checkEvents(node.get("events").asText(), node);
                    break;
                case "record":
                    vobject(resolve(node.get("record").asText())).vmf().changes().start();
                    break;
                case "recorded":
                    int count = vobject(resolve(node.get("recorded").asText())).vmf().changes().all().size();
                    if (count != node.get("count").asInt()) {
                        throw new ScenarioFailure("expected " + node.get("count").asInt()
                                + " recorded changes, found " + count);
                    }
                    break;
                case "undoAll":
                    undoAll(node.get("undoAll").asText());
                    break;
                case "redoAll":
                    for (Change change : undone.remove(node.get("redoAll").asText())) {
                        change.apply(change.object());
                    }
                    break;
                case "properties":
                    List<String> names = vobject(resolve(node.get("properties").asText())).vmf().reflect()
                            .properties().stream().map(Property::getName).collect(Collectors.toList());
                    List<String> expectedNames = new ArrayList<>();
                    node.get("are").forEach(name -> expectedNames.add(name.asText()));
                    if (!names.equals(expectedNames)) {
                        throw new ScenarioFailure("expected properties " + expectedNames + ", found " + names);
                    }
                    break;
                case "annotations":
                    checkAnnotations(node.get("annotations").asText(), node.get("are"));
                    break;
                default:
                    throw new ScenarioFailure("unsupported operation " + operation);
            }
        }

        // ---------------------------------------------------------------------------------------- object access

        Class<?> modelClass(String type) throws ClassNotFoundException {
            return Class.forName(BASE_PACKAGE + model + "." + type);
        }

        Object create(String type, JsonNode with) throws Exception {
            Class<?> modelType = modelClass(type);
            if (with == null || with.isNull()) {
                try {
                    return modelType.getMethod("newInstance").invoke(null);
                } catch (NoSuchMethodException immutable) {
                    // immutable types are created by their builders only
                }
            }
            Method newBuilder = modelType.getMethod("newBuilder");
            Object builder = newBuilder.invoke(null);
            Class<?> builderType = newBuilder.getReturnType();
            if (with != null && !with.isNull()) {
                for (Iterator<Map.Entry<String, JsonNode>> fields = with.fields(); fields.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    applyBuilderValue(builder, builderType, field.getKey(), field.getValue());
                }
            }
            return builderType.getMethod("build").invoke(builder);
        }

        void applyBuilderValue(Object builder, Class<?> builderType, String property, JsonNode value)
                throws Exception {
            String name = "with" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            List<String> attempts = new ArrayList<>();
            for (Method method : builderType.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                    continue;
                }
                Class<?> parameter = method.getParameterTypes()[0];
                Object argument;
                try {
                    if (value.isArray() && parameter.isArray()) {
                        Class<?> component = parameter.getComponentType();
                        argument = Array.newInstance(component, value.size());
                        for (int i = 0; i < value.size(); i++) {
                            Object element = toJava(value.get(i), component);
                            if (element != null && !box(component).isInstance(element)) {
                                throw new IllegalArgumentException("element type");
                            }
                            Array.set(argument, i, element);
                        }
                    } else if (value.isArray() && List.class.isAssignableFrom(parameter)) {
                        List<Object> list = new ArrayList<>();
                        value.forEach(element -> list.add(toJava(element, Object.class)));
                        argument = list;
                    } else if (!value.isArray() && !parameter.isArray()) {
                        argument = toJava(value, parameter);
                        if (argument != null && !box(parameter).isInstance(argument)) {
                            throw new IllegalArgumentException("type");
                        }
                    } else {
                        continue;
                    }
                } catch (IllegalArgumentException mismatch) {
                    attempts.add(parameter.getSimpleName());
                    continue;
                }
                method.invoke(builder, argument);
                return;
            }
            throw new ScenarioFailure("no builder method " + name + " accepts " + value + " (tried " + attempts + ")");
        }

        Object resolve(String path) {
            List<String[]> segments = parse(path);
            Object current = labels.get(segments.get(0)[0]);
            if (current == null && !labels.containsKey(segments.get(0)[0])) {
                throw new ScenarioFailure("unknown label '" + segments.get(0)[0] + "'");
            }
            current = index(current, segments.get(0)[1], path);
            for (String[] segment : segments.subList(1, segments.size())) {
                if (current == null) {
                    throw new ScenarioFailure("null before '" + segment[0] + "' in " + path);
                }
                current = index(property(current, segment[0]).get(), segment[1], path);
            }
            return current;
        }

        static Object index(Object value, String index, String path) {
            if (index == null) {
                return value;
            }
            if (!(value instanceof List)) {
                throw new ScenarioFailure("cannot index " + path);
            }
            return ((List<?>) value).get(Integer.parseInt(index));
        }

        /** Splits {@code a.b[2].c} into (name, index) pairs. */
        static List<String[]> parse(String path) {
            List<String[]> segments = new ArrayList<>();
            for (String part : path.split("\\.")) {
                int bracket = part.indexOf('[');
                segments.add(bracket < 0 ? new String[]{part, null}
                        : new String[]{part.substring(0, bracket), part.substring(bracket + 1, part.length() - 1)});
            }
            return segments;
        }

        /** Resolves everything but the last segment and returns (owner, last segment). */
        Object[] owner(String path) {
            int dot = path.lastIndexOf('.');
            if (dot < 0) {
                throw new ScenarioFailure("expected owner.property in " + path);
            }
            String[] last = parse(path.substring(dot + 1)).get(0);
            return new Object[]{resolve(path.substring(0, dot)), last[0], last[1]};
        }

        static Property property(Object owner, String name) {
            Optional<Property> property = vobject(owner).vmf().reflect().propertyByName(name);
            if (property.isEmpty()) {
                throw new ScenarioFailure(owner.getClass().getSimpleName() + " has no property '" + name + "'");
            }
            return property.get();
        }

        static VObject vobject(Object value) {
            if (!(value instanceof VObject)) {
                throw new ScenarioFailure("not a model object: " + value);
            }
            return (VObject) value;
        }

        static eu.mihosoft.vmf.runtime.core.Content content(Object value) {
            return vobject(value).vmf().content();
        }

        @SuppressWarnings("unchecked")
        List<Object> mutableList(String path) {
            Object value = resolve(path);
            if (!(value instanceof List)) {
                throw new ScenarioFailure(path + " is not a list");
            }
            return (List<Object>) value;
        }

        // ---------------------------------------------------------------------------------------- edits

        void set(String path, JsonNode node) {
            Object[] owner = owner(path);
            Property property = property(owner[0], (String) owner[1]);
            if (owner[2] != null) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) property.get();
                list.set(Integer.parseInt((String) owner[2]), single(node, elementType(property)));
                return;
            }
            property.set(single(node, scalarType(property)));
        }

        void add(String path, JsonNode node, int index) {
            Object[] owner = owner(path);
            Property property = property(owner[0], (String) owner[1]);
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) property.get();
            if (node.has("objs") || node.has("values")) {
                List<Object> values = many(node, elementType(property));
                if (index < 0) {
                    list.addAll(values);
                } else {
                    list.addAll(index, values);
                }
            } else if (index < 0) {
                list.add(single(node, elementType(property)));
            } else {
                list.add(index, single(node, elementType(property)));
            }
        }

        void remove(String path, JsonNode node) {
            List<Object> list = mutableList(path);
            if (node.has("at")) {
                list.remove(node.get("at").asInt());
                return;
            }
            boolean identity = node.has("obj");
            Object target = identity ? object(node.get("obj")) : toJava(node.get("value"), Object.class);
            for (int i = 0; i < list.size(); i++) {
                Object element = list.get(i);
                if (identity ? element == target : sameScalar(element, node.get("value"))) {
                    list.remove(i);
                    return;
                }
            }
            throw new ScenarioFailure("nothing to remove from " + path);
        }

        Object single(JsonNode node, Class<?> type) {
            if (node.has("obj")) {
                return object(node.get("obj"));
            }
            if (node.has("value")) {
                return toJava(node.get("value"), type);
            }
            throw new ScenarioFailure("missing 'obj' or 'value'");
        }

        List<Object> many(JsonNode node, Class<?> type) {
            List<Object> values = new ArrayList<>();
            if (node.has("objs")) {
                node.get("objs").forEach(label -> values.add(object(label)));
            } else {
                node.get("values").forEach(value -> values.add(toJava(value, type)));
            }
            return values;
        }

        Object object(JsonNode label) {
            if (label == null || label.isNull()) {
                return null;
            }
            return resolve(label.asText());
        }

        static Class<?> scalarType(Property property) {
            return javaType(property.getType().getName());
        }

        static Class<?> elementType(Property property) {
            return javaType(property.getType().getElementTypeName().orElse("java.lang.Object"));
        }

        static Class<?> javaType(String name) {
            switch (name) {
                case "int":
                case "java.lang.Integer":
                    return Integer.class;
                case "long":
                case "java.lang.Long":
                    return Long.class;
                case "double":
                case "java.lang.Double":
                    return Double.class;
                case "boolean":
                case "java.lang.Boolean":
                    return Boolean.class;
                case "java.lang.String":
                    return String.class;
                default:
                    return Object.class;
            }
        }

        static Class<?> box(Class<?> type) {
            if (!type.isPrimitive()) {
                return type;
            }
            if (type == int.class) return Integer.class;
            if (type == long.class) return Long.class;
            if (type == double.class) return Double.class;
            if (type == boolean.class) return Boolean.class;
            if (type == float.class) return Float.class;
            if (type == short.class) return Short.class;
            if (type == byte.class) return Byte.class;
            return Character.class;
        }

        /** Converts a JSON value; {@code {"obj": label}} denotes a model object. */
        Object toJava(JsonNode value, Class<?> type) {
            if (value == null || value.isNull()) {
                return null;
            }
            if (value.isObject() && value.has("obj")) {
                return object(value.get("obj"));
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isTextual()) {
                return value.asText();
            }
            if (value.isNumber()) {
                Class<?> boxed = box(type);
                if (boxed == Long.class) return value.asLong();
                if (boxed == Double.class) return value.asDouble();
                if (boxed == Float.class) return (float) value.asDouble();
                if (boxed == Integer.class) return value.asInt();
                return value.isIntegralNumber() ? (Object) value.asInt() : (Object) value.asDouble();
            }
            throw new ScenarioFailure("cannot convert " + value);
        }

        // ---------------------------------------------------------------------------------------- checks

        void check(String path, JsonNode node) {
            Object actual = resolve(path);
            if (node.has("obj")) {
                Object expected = object(node.get("obj"));
                if (actual != expected) {
                    throw new ScenarioFailure(path + ": expected " + render(expected) + ", found " + render(actual));
                }
            }
            if (node.has("value") && !sameScalar(actual, node.get("value"))) {
                throw new ScenarioFailure(path + ": expected " + node.get("value") + ", found " + render(actual));
            }
            if (node.has("objs") || node.has("values") || node.has("size")) {
                if (!(actual instanceof Collection)) {
                    throw new ScenarioFailure(path + " is not a collection: " + render(actual));
                }
                List<Object> items = new ArrayList<>((Collection<?>) actual);
                if (node.has("size") && items.size() != node.get("size").asInt()) {
                    throw new ScenarioFailure(path + ": expected size " + node.get("size") + ", found "
                            + items.size() + " " + render(items));
                }
                if (node.has("objs")) {
                    List<Object> expected = new ArrayList<>();
                    node.get("objs").forEach(label -> expected.add(object(label)));
                    if (!sameObjects(items, expected, node.path("unordered").asBoolean(false))) {
                        throw new ScenarioFailure(path + ": expected " + render(expected) + ", found " + render(items));
                    }
                }
                if (node.has("values")) {
                    JsonNode expected = node.get("values");
                    boolean same = items.size() == expected.size();
                    for (int i = 0; same && i < items.size(); i++) {
                        same = sameScalar(items.get(i), expected.get(i));
                    }
                    if (!same) {
                        throw new ScenarioFailure(path + ": expected " + expected + ", found " + render(items));
                    }
                }
            }
        }

        static boolean sameObjects(List<Object> actual, List<Object> expected, boolean unordered) {
            if (actual.size() != expected.size()) {
                return false;
            }
            if (!unordered) {
                for (int i = 0; i < actual.size(); i++) {
                    if (actual.get(i) != expected.get(i)) {
                        return false;
                    }
                }
                return true;
            }
            List<Object> remaining = new ArrayList<>(expected);
            for (Object item : actual) {
                boolean found = false;
                for (int i = 0; i < remaining.size(); i++) {
                    if (remaining.get(i) == item) {
                        remaining.remove(i);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }
            return true;
        }

        static boolean sameScalar(Object actual, JsonNode expected) {
            if (expected == null || expected.isNull()) {
                return actual == null;
            }
            if (actual == null) {
                return false;
            }
            if (expected.isNumber()) {
                if (!(actual instanceof Number)) {
                    return false;
                }
                Number number = (Number) actual;
                return expected.isIntegralNumber() && !(actual instanceof Double || actual instanceof Float)
                        ? number.longValue() == expected.asLong()
                        : number.doubleValue() == expected.asDouble();
            }
            if (expected.isBoolean()) {
                return actual.equals(expected.asBoolean());
            }
            if (expected.isTextual()) {
                return actual instanceof String && actual.equals(expected.asText());
            }
            if (expected.isArray()) {
                if (!(actual instanceof List) || ((List<?>) actual).size() != expected.size()) {
                    return false;
                }
                for (int i = 0; i < expected.size(); i++) {
                    if (!sameScalar(((List<?>) actual).get(i), expected.get(i))) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }

        interface Pair {
            void check(Object left, Object right) throws Exception;
        }

        void pairs(JsonNode paths, Pair check) throws Exception {
            Object left = resolve(paths.get(0).asText());
            for (int i = 1; i < paths.size(); i++) {
                Object right = resolve(paths.get(i).asText());
                try {
                    check.check(left, right);
                } catch (ScenarioFailure failure) {
                    throw new ScenarioFailure(paths.get(0).asText() + " vs " + paths.get(i).asText() + ": "
                            + failure.getMessage());
                }
            }
        }

        void checkAnnotations(String path, JsonNode expected) {
            List<Annotation> annotations;
            if (path.contains(".")) {
                Object[] owner = owner(path);
                annotations = property(owner[0], (String) owner[1]).annotations();
            } else {
                annotations = vobject(resolve(path)).vmf().reflect().annotations();
            }
            List<String> actual = annotations.stream().filter(a -> !a.getKey().startsWith("vmf:"))
                    .map(a -> a.getKey() + "=" + a.getValue()).collect(Collectors.toList());
            List<String> wanted = new ArrayList<>();
            expected.forEach(pair -> wanted.add(pair.get(0).asText() + "=" + pair.get(1).asText()));
            if (!actual.equals(wanted)) {
                throw new ScenarioFailure(path + ": expected annotations " + wanted + ", found " + actual);
            }
        }

        // ---------------------------------------------------------------------------------------- events

        void listen(String path, String name, String scope) {
            List<Event> received = events.computeIfAbsent(name, key -> new ArrayList<>());
            Subscription subscription = vobject(resolve(path)).vmf().changes().addListener(change ->
                    received.add(toEvent(change)), !"local".equals(scope));
            subscriptions.put(name, subscription);
        }

        Event toEvent(Change change) {
            if (change.propertyChange().isPresent()) {
                return new Event(step, change.object(), change.propertyName(), "set",
                        change.propertyChange().get().oldValue(), change.propertyChange().get().newValue(),
                        List.of(), List.of());
            }
            if (change.listChange().isPresent()) {
                VListChangeEvent<Object> list = change.listChange().get();
                String kind = list.wasSet() ? "replace" : list.wasAdded() ? "add" : "remove";
                List<Object> elements = new ArrayList<>(
                        list.wasAdded() || list.wasSet() ? list.added().elements() : list.removed().elements());
                int[] indices = list.wasAdded() || list.wasSet() ? list.added().indices() : list.removed().indices();
                return new Event(step, change.object(), change.propertyName(), kind, null, null, elements,
                        Arrays.stream(indices).boxed().collect(Collectors.toList()));
            }
            return new Event(step, change.object(), change.propertyName(), "other", null, null, List.of(), List.of());
        }

        /**
         * Normalizes the events of one step: consecutive list events of the same kind on the same property are
         * merged (bulk edits), and a chain of set events on the same property (a -> b, b -> c) becomes one event
         * (a -> c), so a move reported as detach plus attach equals a move reported at once.
         */
        static List<Event> merged(List<Event> received) {
            List<Event> result = new ArrayList<>();
            for (Event event : received) {
                Event last = result.isEmpty() ? null : result.get(result.size() - 1);
                boolean sameTarget = last != null && last.step == event.step && last.source == event.source
                        && last.property.equals(event.property) && last.kind.equals(event.kind);
                if (sameTarget && (event.kind.equals("add") || event.kind.equals("remove"))) {
                    List<Object> elements = new ArrayList<>(last.elements);
                    elements.addAll(event.elements);
                    List<Integer> indices = new ArrayList<>(last.indices);
                    indices.addAll(event.indices);
                    result.set(result.size() - 1, new Event(last.step, last.source, last.property, last.kind,
                            null, null, elements, indices));
                } else if (sameTarget && event.kind.equals("set") && chained(last.newValue, event.oldValue)) {
                    result.set(result.size() - 1, new Event(last.step, last.source, last.property, "set",
                            last.oldValue, event.newValue, List.of(), List.of()));
                } else {
                    result.add(event);
                }
            }
            return result;
        }

        static boolean chained(Object previousNewValue, Object nextOldValue) {
            if (previousNewValue instanceof VObject || nextOldValue instanceof VObject) {
                return previousNewValue == nextOldValue;
            }
            return java.util.Objects.equals(previousNewValue, nextOldValue);
        }

        void checkEvents(String name, JsonNode node) {
            List<Event> buffer = events.computeIfAbsent(name, key -> new ArrayList<>());
            List<Event> received = merged(new ArrayList<>(buffer));
            buffer.clear();
            if (node.has("ignore")) {
                received.removeIf(event -> {
                    for (JsonNode pattern : node.get("ignore")) {
                        if (matches(pattern, event)) {
                            return true;
                        }
                    }
                    return false;
                });
            }
            if (node.has("count")) {
                if (received.size() != node.get("count").asInt()) {
                    throw new ScenarioFailure(name + ": expected " + node.get("count") + " events, received "
                            + render(received));
                }
                return;
            }
            JsonNode expected = node.get("are");
            if (expected.size() != received.size()) {
                throw new ScenarioFailure(name + ": expected " + expected + ", received " + render(received));
            }
            List<Event> remaining = new ArrayList<>(received);
            boolean anyOrder = node.path("anyOrder").asBoolean(false);
            for (int i = 0; i < expected.size(); i++) {
                JsonNode wanted = expected.get(i);
                int match = -1;
                for (int j = 0; j < (anyOrder ? remaining.size() : 1); j++) {
                    if (matches(wanted, remaining.get(j))) {
                        match = j;
                        break;
                    }
                }
                if (match < 0) {
                    throw new ScenarioFailure(name + ": expected " + expected + ", received " + render(received));
                }
                remaining.remove(match);
            }
        }

        boolean matches(JsonNode wanted, Event event) {
            if (wanted.has("on") && resolve(wanted.get("on").asText()) != event.source) return false;
            if (wanted.has("prop") && !wanted.get("prop").asText().equals(event.property)) return false;
            if (wanted.has("kind") && !wanted.get("kind").asText().equals(event.kind)) return false;
            if (wanted.has("old") && !sameScalar(event.oldValue, wanted.get("old"))) return false;
            if (wanted.has("new") && !sameScalar(event.newValue, wanted.get("new"))) return false;
            if (wanted.has("oldObj") && object(wanted.get("oldObj")) != event.oldValue) return false;
            if (wanted.has("newObj") && object(wanted.get("newObj")) != event.newValue) return false;
            if (wanted.has("values") && !sameScalar(event.elements, wanted.get("values"))) return false;
            if (wanted.has("objs")) {
                List<Object> expected = new ArrayList<>();
                wanted.get("objs").forEach(label -> expected.add(object(label)));
                if (!sameObjects(event.elements, expected, false)) return false;
            }
            if (wanted.has("index")) {
                List<Integer> indices = new ArrayList<>();
                wanted.get("index").forEach(index -> indices.add(index.asInt()));
                return indices.equals(event.indices);
            }
            return true;
        }

        // ---------------------------------------------------------------------------------------- history

        void undoAll(String label) {
            VObject owner = vobject(resolve(label));
            owner.vmf().changes().stop();
            List<Change> changes = new ArrayList<>(owner.vmf().changes().all());
            List<Change> reversed = new ArrayList<>(changes);
            Collections.reverse(reversed);
            for (Change change : reversed) {
                change.undo();
            }
            undone.put(label, changes);
        }

        // ---------------------------------------------------------------------------------------- rendering

        String render(Object value) {
            if (value == null) {
                return "null";
            }
            if (value instanceof Event) {
                Event event = (Event) value;
                return "{" + render(event.source) + "." + event.property + " " + event.kind
                        + (event.kind.equals("set") ? " " + render(event.oldValue) + "->" + render(event.newValue)
                        : " " + render(event.elements) + "@" + event.indices) + "}";
            }
            if (value instanceof Collection) {
                return ((Collection<?>) value).stream().map(this::render).collect(Collectors.joining(", ", "[", "]"));
            }
            if (value instanceof VObject || value.getClass().getName().startsWith(BASE_PACKAGE)) {
                for (Map.Entry<String, Object> label : labels.entrySet()) {
                    if (label.getValue() == value) {
                        return label.getKey();
                    }
                }
                return "<" + value.getClass().getSimpleName().replace("Impl", "") + ">";
            }
            return value instanceof String ? "\"" + value + "\"" : String.valueOf(value);
        }

        /** A textual dump of every object reachable from a label, used to prove rejected edits changed nothing. */
        String fingerprint() {
            Map<Object, Integer> ids = new IdentityHashMap<>();
            List<Object> pending = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, Object> label : labels.entrySet()) {
                text.append(label.getKey()).append('=').append(reference(label.getValue(), ids, pending)).append(' ');
            }
            for (int i = 0; i < pending.size(); i++) {
                Object current = pending.get(i);
                text.append('#').append(ids.get(current)).append('{');
                for (Property property : vobject(current).vmf().reflect().properties()) {
                    Object value = property.get();
                    text.append(property.getName()).append('=');
                    if (value instanceof Collection) {
                        text.append('[');
                        for (Object element : (Collection<?>) value) {
                            text.append(reference(element, ids, pending)).append(',');
                        }
                        text.append(']');
                    } else {
                        text.append(reference(value, ids, pending));
                    }
                    text.append(';');
                }
                text.append("} ");
            }
            return text.toString();
        }

        static String reference(Object value, Map<Object, Integer> ids, List<Object> pending) {
            if (value instanceof VObject) {
                Integer id = ids.get(value);
                if (id == null) {
                    id = ids.size();
                    ids.put(value, id);
                    pending.add(value);
                }
                return "#" + id;
            }
            return String.valueOf(value);
        }
    }
}
