package eu.mihosoft.vmf.portable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.OrderedRefSet;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.RefSet;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.ValidationReport;
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
            "core", "views", "events", "history", "reflection", "equality-modes", "json", "validation",
            "collections", "externals", "behavior");
    /** Converts host values (enums, UUIDs, untyped values, external types) as documents do. */
    static final ObjectMapper JSON = new ObjectMapper();
    /** Modules that do not apply to the language, with the reason. */
    static final Map<String, String> NOT_APPLICABLE = Map.of(
            "identity-equality", "Java's equals and hashCode follow the declared mode (N2)");

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
                String inapplicable = requires.stream().filter(NOT_APPLICABLE::containsKey).findFirst().orElse(null);
                if (inapplicable != null) {
                    result.status = "skipped";
                    result.message = inapplicable + " does not apply: " + NOT_APPLICABLE.get(inapplicable);
                } else if (!CAPABILITIES.containsAll(requires)) {
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
        /** The keys of a map event, one per element. */
        final List<Object> keys;

        Event(int step, Object source, String property, String kind, Object oldValue, Object newValue,
              List<Object> elements, List<Integer> indices) {
            this(step, source, property, kind, oldValue, newValue, elements, indices, List.of());
        }

        Event(int step, Object source, String property, String kind, Object oldValue, Object newValue,
              List<Object> elements, List<Integer> indices, List<Object> keys) {
            this.step = step;
            this.source = source;
            this.property = property;
            this.kind = kind;
            this.oldValue = oldValue;
            this.newValue = newValue;
            this.elements = elements;
            this.indices = indices;
            this.keys = keys;
        }
    }

    static final class Execution {
        static final List<String> OPERATIONS = List.of("new", "set", "add", "insert", "remove", "clear", "check",
                "let", "equal", "notEqual", "nativeEqual", "nativeNotEqual", "same", "notSame", "copy", "readOnly",
                "instanceOf", "notInstanceOf", "listen", "unlisten", "events", "record", "recorded", "undoAll",
                "redoAll", "properties", "annotations", "write", "read", "document", "calls", "validate", "put",
                "putAll", "removeKey", "find", "call", "replaceBehavior");

        final String model;
        final Map<String, Object> labels = new LinkedHashMap<>();
        final Map<String, List<Event>> events = new HashMap<>();
        /** Shared call logs: which listener received which event, in delivery order. */
        final Map<String, List<Object[]>> calls = new HashMap<>();
        final Map<String, Subscription> subscriptions = new HashMap<>();
        final Map<String, List<Change>> undone = new HashMap<>();
        final Map<String, String> documents = new HashMap<>();
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
                    clear(node.get("clear").asText());
                    break;
                case "put": {
                    Object[] owner = owner(node.get("put").asText());
                    Property property = property(owner[0], (String) owner[1]);
                    Class<?>[] types = mapTypes(property);
                    map(property).put(toJava(node.get("key"), types[0]), toJava(node.get("value"), types[1]));
                    break;
                }
                case "putAll": {
                    Object[] owner = owner(node.get("putAll").asText());
                    Property property = property(owner[0], (String) owner[1]);
                    map(property).putAll(entries(node.get("entries"), mapTypes(property)));
                    break;
                }
                case "removeKey": {
                    Object[] owner = owner(node.get("removeKey").asText());
                    Property property = property(owner[0], (String) owner[1]);
                    map(property).remove(toJava(node.get("key"), mapTypes(property)[0]));
                    break;
                }
                case "find":
                    find(node.get("find").asText(), node);
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
                            node.path("scope").asText("recursive"), node);
                    break;
                case "calls":
                    checkCalls(node.get("calls").asText(), node.get("are"));
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
                case "write": {
                    Object root = resolve(node.get("write").asText());
                    documents.put(node.get("as").asText(), mapper(modelInterface(root), node.path("plain").asBoolean(false),
                            "omit".equals(node.path("references").asText())).writeValueAsString(root));
                    break;
                }
                case "read": {
                    Class<?> type = modelClass(node.get("type").asText());
                    labels.put(node.get("as").asText(), mapper(type, node.path("plain").asBoolean(false), false)
                            .readValue(document(node.get("read")), type));
                    break;
                }
                case "document": {
                    String name = node.get("document").asText();
                    JsonNode actual = new ObjectMapper().readTree(document(node.get("document")));
                    if (!sameJson(actual, node.get("is"))) {
                        throw new ScenarioFailure(name + ": expected " + node.get("is") + ", written " + actual);
                    }
                    break;
                }
                case "validate":
                    checkIssues(node.get("validate").asText(), node.get("issues"));
                    break;
                case "call":
                    call(node.get("call").asText(), node);
                    break;
                case "replaceBehavior":
                    replaceBehavior(node.get("replaceBehavior").asText(), node.get("with").asText());
                    break;
                default:
                    throw new ScenarioFailure("unsupported operation " + operation);
            }
        }

        // ---------------------------------------------------------------------------------------- JSON

        /** A written document by name, or a literal {"json": ...} document from the scenario. */
        String document(JsonNode source) throws IOException {
            if (source.isTextual()) {
                String text = documents.get(source.asText());
                if (text == null) {
                    throw new ScenarioFailure("unknown document '" + source.asText() + "'");
                }
                return text;
            }
            return new ObjectMapper().writeValueAsString(source.get("json"));
        }

        static ObjectMapper mapper(Class<?> modelType, boolean plain, boolean omitReferences) {
            VMFJacksonModule module = VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL);
            @SuppressWarnings("unchecked")
            Class<? extends VObject> vmfType = (Class<? extends VObject>) modelType;
            return new ObjectMapper().registerModule(plain
                    ? module.withReferences(omitReferences ? VMFJacksonModule.ReferenceMode.OMIT
                            : VMFJacksonModule.ReferenceMode.REJECT)
                    : module.withModelWireTypeAliases(vmfType).withReferences(true));
        }

        Class<?> modelInterface(Object value) {
            for (Class<?> candidate = value.getClass(); candidate != null; candidate = candidate.getSuperclass()) {
                for (Class<?> type : candidate.getInterfaces()) {
                    if (type.getName().startsWith(BASE_PACKAGE + model + ".") && !type.getSimpleName().startsWith("ReadOnly")) {
                        return type;
                    }
                }
            }
            throw new ScenarioFailure("not a model object: " + value);
        }

        /** JSON equality: objects ignore key order, numbers compare by value. */
        static boolean sameJson(JsonNode actual, JsonNode expected) {
            if (expected.isNumber() || actual.isNumber()) {
                if (!expected.isNumber() || !actual.isNumber()) {
                    return false;
                }
                return expected.isIntegralNumber() && actual.isIntegralNumber()
                        ? expected.bigIntegerValue().equals(actual.bigIntegerValue())
                        : expected.decimalValue().compareTo(actual.decimalValue()) == 0;
            }
            if (expected.isObject()) {
                if (!actual.isObject() || actual.size() != expected.size()) {
                    return false;
                }
                for (Iterator<Map.Entry<String, JsonNode>> fields = expected.fields(); fields.hasNext(); ) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (!actual.has(field.getKey()) || !sameJson(actual.get(field.getKey()), field.getValue())) {
                        return false;
                    }
                }
                return true;
            }
            if (expected.isArray()) {
                if (!actual.isArray() || actual.size() != expected.size()) {
                    return false;
                }
                for (int i = 0; i < expected.size(); i++) {
                    if (!sameJson(actual.get(i), expected.get(i))) {
                        return false;
                    }
                }
                return true;
            }
            return expected.equals(actual);
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
            // Prefer array (varargs) overloads; their component type converts the elements exactly.
            List<Method> methods = new ArrayList<>(Arrays.asList(builderType.getMethods()));
            methods.sort((a, b) -> Boolean.compare(!takesArray(a), !takesArray(b)));
            for (Method method : methods) {
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
                        Class<?> element = Object.class;
                        java.lang.reflect.Type generic = method.getGenericParameterTypes()[0];
                        if (generic instanceof java.lang.reflect.ParameterizedType) {
                            java.lang.reflect.Type argumentType =
                                    ((java.lang.reflect.ParameterizedType) generic).getActualTypeArguments()[0];
                            if (argumentType instanceof Class) {
                                element = (Class<?>) argumentType;
                            }
                        }
                        List<Object> list = new ArrayList<>();
                        for (JsonNode item : value) {
                            list.add(toJava(item, element));
                        }
                        argument = list;
                    } else if (value.isObject() && value.has("entries") && Map.class.isAssignableFrom(parameter)) {
                        Class<?>[] types = {Object.class, Object.class};
                        java.lang.reflect.Type generic = method.getGenericParameterTypes()[0];
                        if (generic instanceof java.lang.reflect.ParameterizedType) {
                            java.lang.reflect.Type[] arguments =
                                    ((java.lang.reflect.ParameterizedType) generic).getActualTypeArguments();
                            for (int i = 0; i < 2; i++) {
                                if (arguments[i] instanceof Class) {
                                    types[i] = (Class<?>) arguments[i];
                                }
                            }
                        }
                        argument = entries(value.get("entries"), types);
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

        static boolean takesArray(Method method) {
            return method.getParameterCount() == 1 && method.getParameterTypes()[0].isArray();
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
            if (value instanceof OrderedRefSet) {
                return ((OrderedRefSet<?>) value).get(Integer.parseInt(index));
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

        // ---------------------------------------------------------------------------------------- edits

        void set(String path, JsonNode node) {
            Object[] owner = owner(path);
            Property property = property(owner[0], (String) owner[1]);
            if (owner[2] != null) {
                int index = Integer.parseInt((String) owner[2]);
                if (property.get() instanceof OrderedRefSet) {
                    @SuppressWarnings("unchecked")
                    OrderedRefSet<VObject> set = (OrderedRefSet<VObject>) property.get();
                    set.set(index, (VObject) single(node, elementType(property)));
                    return;
                }
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) property.get();
                list.set(index, single(node, elementType(property)));
                return;
            }
            property.set(single(node, scalarType(property)));
        }

        /** Appends to a list or adds to a set; several values are added in one call. */
        void add(String path, JsonNode node, int index) {
            Object[] owner = owner(path);
            Property property = property(owner[0], (String) owner[1]);
            if (property.get() instanceof RefSet) {
                @SuppressWarnings("unchecked")
                RefSet<VObject> set = (RefSet<VObject>) property.get();
                List<Object> values = node.has("objs") ? many(node, Object.class) : List.of(single(node, Object.class));
                if (index < 0) {
                    for (Object value : values) {
                        set.add((VObject) value);
                    }
                } else if (set instanceof OrderedRefSet) {
                    for (int i = 0; i < values.size(); i++) {
                        ((OrderedRefSet<VObject>) set).add(index + i, (VObject) values.get(i));
                    }
                } else {
                    throw new ScenarioFailure(path + " is unordered");
                }
                return;
            }
            @SuppressWarnings("unchecked")
            Collection<Object> collection = (Collection<Object>) property.get();
            if (index >= 0 && !(collection instanceof List)) {
                throw new ScenarioFailure(path + " is unordered");
            }
            if (node.has("objs") || node.has("values")) {
                List<Object> values = many(node, elementType(property));
                if (index < 0) {
                    collection.addAll(values);
                } else {
                    ((List<Object>) collection).addAll(index, values);
                }
            } else if (index < 0) {
                collection.add(single(node, elementType(property)));
            } else {
                ((List<Object>) collection).add(index, single(node, elementType(property)));
            }
        }

        void remove(String path, JsonNode node) {
            Object resolved = resolve(path);
            if (resolved instanceof RefSet) {
                RefSet<?> set = (RefSet<?>) resolved;
                if (node.has("at")) {
                    ((OrderedRefSet<?>) set).remove(node.get("at").asInt());
                } else if (!set.remove(object(node.get("obj")))) {
                    throw new ScenarioFailure("nothing to remove from " + path);
                }
                return;
            }
            Collection<Object> collection = mutableCollection(path);
            if (node.has("at")) {
                ((List<Object>) collection).remove(node.get("at").asInt());
                return;
            }
            boolean identity = node.has("obj");
            Object target = identity ? object(node.get("obj")) : null;
            int position = 0;
            for (Object element : collection) {
                if (identity ? element == target : sameScalar(element, node.get("value"))) {
                    if (collection instanceof List) {
                        ((List<Object>) collection).remove(position);
                    } else {
                        collection.remove(element);
                    }
                    return;
                }
                position++;
            }
            throw new ScenarioFailure("nothing to remove from " + path);
        }

        void clear(String path) {
            Object value = resolve(path);
            if (value instanceof Map) {
                ((Map<?, ?>) value).clear();
            } else if (value instanceof RefSet) {
                ((RefSet<?>) value).clear();
            } else {
                mutableCollection(path).clear();
            }
        }

        @SuppressWarnings("unchecked")
        Collection<Object> mutableCollection(String path) {
            Object value = resolve(path);
            if (!(value instanceof Collection)) {
                throw new ScenarioFailure(path + " is not a collection");
            }
            return (Collection<Object>) value;
        }

        @SuppressWarnings("unchecked")
        static Map<Object, Object> map(Property property) {
            Object value = property.get();
            if (!(value instanceof Map)) {
                throw new ScenarioFailure(property.getName() + " is not a map");
            }
            return (Map<Object, Object>) value;
        }

        /** The key and value types of a map property. */
        static Class<?>[] mapTypes(Property property) {
            var arguments = property.getType().getDescriptor().getArguments();
            return new Class<?>[]{javaType(arguments.get(0).getName()), javaType(arguments.get(1).getName())};
        }

        /** Map entries written as {@code [[key, value], ...]}, in their order. */
        Map<Object, Object> entries(JsonNode pairs, Class<?>[] types) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (JsonNode pair : pairs) {
                result.put(toJava(pair.get(0), types[0]), toJava(pair.get(1), types[1]));
            }
            return result;
        }

        /** Calls the generated lookup {@code find<Property>By<Key>} of a keyed collection's owner. */
        void find(String path, JsonNode node) throws Exception {
            Object[] owner = owner(path);
            String name = "find" + capitalize((String) owner[1]) + "By" + capitalize(node.get("by").asText());
            Method lookup = Arrays.stream(owner[0].getClass().getMethods())
                    .filter(method -> method.getName().equals(name) && method.getParameterCount() == 1)
                    .findFirst().orElseThrow(() -> new ScenarioFailure("no lookup " + name));
            lookup.setAccessible(true);
            Object found = ((Optional<?>) lookup.invoke(owner[0],
                    toJava(node.get("key"), lookup.getParameterTypes()[0]))).orElse(null);
            Object expected = object(node.get("obj"));
            if (found != expected) {
                throw new ScenarioFailure(name + "(" + node.get("key") + "): expected " + render(expected) + ", found "
                        + render(found));
            }
        }

        static String capitalize(String name) {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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

        /**
         * Calls a delegated method of the model interface; the last arguments fill a variable-argument array. Checks
         * the result against "result" (a value) or "resultObj" (a label).
         */
        void call(String path, JsonNode node) throws Exception {
            Object[] owner = owner(path);
            Object target = owner[0];
            JsonNode args = node.path("args");
            Method method = null;
            for (Method candidate : modelInterface(target).getMethods()) {
                int count = candidate.getParameterCount();
                if (candidate.getName().equals(owner[1])
                        && (count == args.size() || candidate.isVarArgs() && args.size() >= count - 1)) {
                    method = candidate;
                    break;
                }
            }
            if (method == null) {
                throw new ScenarioFailure(path + " is not a method that takes " + args.size() + " arguments");
            }
            Class<?>[] types = method.getParameterTypes();
            Object[] arguments = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                if (method.isVarArgs() && i == types.length - 1) {
                    Object rest = Array.newInstance(types[i].getComponentType(), args.size() - i);
                    for (int j = i; j < args.size(); j++) {
                        Array.set(rest, j - i, toJava(args.get(j), types[i].getComponentType()));
                    }
                    arguments[i] = rest;
                } else {
                    arguments[i] = toJava(args.get(i), types[i]);
                }
            }
            Object result = method.invoke(target, arguments);
            if (node.has("result") && !sameScalar(result, node.get("result"))) {
                throw new ScenarioFailure(path + ": expected " + node.get("result") + ", returned " + render(result));
            }
            if (node.has("resultObj") && result != object(node.get("resultObj"))) {
                throw new ScenarioFailure(path + ": expected " + node.get("resultObj") + ", returned " + render(result));
            }
        }

        /** Replaces the behavior of one object with a new instance of a host class of the model's package. */
        @SuppressWarnings({"unchecked", "rawtypes"})
        void replaceBehavior(String path, String name) throws Exception {
            Object behavior = Class.forName(BASE_PACKAGE + model + "." + name).getConstructor().newInstance();
            vobject(resolve(path)).vmf().behavior().set((eu.mihosoft.vmf.runtime.core.DelegatedBehavior) behavior);
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
                case "byte":
                case "java.lang.Byte":
                    return Byte.class;
                case "short":
                case "java.lang.Short":
                    return Short.class;
                case "float":
                case "java.lang.Float":
                    return Float.class;
                case "char":
                case "java.lang.Character":
                    return Character.class;
                case "java.lang.String":
                    return String.class;
                default:
                    return hostType(name);
            }
        }

        /** An external host type such as an enum, UUID or value class; Object if it cannot be loaded. */
        static Class<?> hostType(String name) {
            for (String binary = name; ; ) {
                try {
                    return Class.forName(binary);
                } catch (ClassNotFoundException missing) {
                    // A nested class has a binary name with '$'.
                    int dot = binary.lastIndexOf('.');
                    if (dot < 0) {
                        return Object.class;
                    }
                    binary = binary.substring(0, dot) + "$" + binary.substring(dot + 1);
                }
            }
        }

        static boolean isPortableScalar(Class<?> type) {
            return type == String.class || type == Boolean.class || type == Integer.class || type == Long.class
                    || type == Double.class || type == Float.class || type == Short.class || type == Byte.class
                    || type == Character.class;
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
            Class<?> host = box(type);
            if (!isPortableScalar(host) && !VObject.class.isAssignableFrom(host)) {
                // Untyped values, enums, UUIDs and external value types convert as documents do.
                try {
                    return JSON.treeToValue(value, host);
                } catch (com.fasterxml.jackson.core.JsonProcessingException mismatch) {
                    throw new IllegalArgumentException(mismatch.getMessage(), mismatch);
                }
            }
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isTextual()) {
                // A char is one UTF-16 code unit (T6).
                if (box(type) == Character.class && value.asText().length() == 1) return value.asText().charAt(0);
                return value.asText();
            }
            if (value.isNumber()) {
                Class<?> boxed = box(type);
                if (boxed == Long.class) return value.asLong();
                if (boxed == Double.class) return value.asDouble();
                if (boxed == Float.class) return (float) value.asDouble();
                if (boxed == Integer.class) return value.asInt();
                if (boxed == Short.class) return (short) value.asInt();
                if (boxed == Byte.class) return (byte) value.asInt();
                return value.isIntegralNumber() ? (Object) value.asInt() : (Object) value.asDouble();
            }
            throw new ScenarioFailure("cannot convert " + value);
        }

        // ---------------------------------------------------------------------------------------- checks

        void check(String path, JsonNode node) {
            Object actual = resolve(path);
            // An untyped value can be a Map too (externals); it is compared as a value.
            if (actual instanceof Map && !node.has("value")) {
                checkMap(path, (Map<?, ?>) actual, node);
                return;
            }
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
                if (!(actual instanceof Collection) && !(actual instanceof RefSet)) {
                    throw new ScenarioFailure(path + " is not a collection: " + render(actual));
                }
                List<Object> items = members(actual);
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
                    if (!sameValues(items, expected, node.path("unordered").asBoolean(false))) {
                        throw new ScenarioFailure(path + ": expected " + expected + ", found " + render(items));
                    }
                }
            }
        }

        /** Scalars in order, or in any order (each expected value matches one element). */
        static boolean sameValues(List<?> items, JsonNode expected, boolean unordered) {
            if (items.size() != expected.size()) {
                return false;
            }
            List<Object> remaining = new ArrayList<>(items);
            for (int i = 0; i < expected.size(); i++) {
                int match = -1;
                for (int j = 0; j < (unordered ? remaining.size() : Math.min(1, remaining.size())); j++) {
                    if (sameScalar(remaining.get(j), expected.get(i))) {
                        match = j;
                        break;
                    }
                }
                if (match < 0) {
                    return false;
                }
                remaining.remove(match);
            }
            return true;
        }

        /** A map: {@code size} and {@code entries} ({@code [[key, value], ...]}) in iteration order or unordered. */
        void checkMap(String path, Map<?, ?> actual, JsonNode node) {
            if (node.has("size") && actual.size() != node.get("size").asInt()) {
                throw new ScenarioFailure(path + ": expected size " + node.get("size") + ", found " + actual.size()
                        + " " + actual);
            }
            if (node.has("entries")) {
                JsonNode expected = node.get("entries");
                List<Map.Entry<?, ?>> remaining = new ArrayList<>(actual.entrySet());
                boolean same = remaining.size() == expected.size();
                boolean unordered = node.path("unordered").asBoolean(false);
                for (int i = 0; same && i < expected.size(); i++) {
                    int match = -1;
                    for (int j = 0; j < (unordered ? remaining.size() : 1); j++) {
                        if (sameScalar(remaining.get(j).getKey(), expected.get(i).get(0))
                                && sameScalar(remaining.get(j).getValue(), expected.get(i).get(1))) {
                            match = j;
                            break;
                        }
                    }
                    same = match >= 0;
                    if (same) {
                        remaining.remove(match);
                    }
                }
                if (!same) {
                    throw new ScenarioFailure(path + ": expected " + expected + ", found " + actual);
                }
            }
            if (node.has("obj") || node.has("value") || node.has("objs") || node.has("values")) {
                throw new ScenarioFailure(path + " is a map");
            }
        }

        /** The elements of a collection or reference set in iteration order. */
        static List<Object> members(Object collection) {
            List<Object> items = new ArrayList<>();
            ((Iterable<?>) collection).forEach(items::add);
            return items;
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
            if (expected.isObject() || !(actual instanceof Number || actual instanceof Boolean
                    || actual instanceof String || actual instanceof Character || actual instanceof List)) {
                // Untyped maps, enums, UUIDs and external values compare in their document form.
                return sameJson(JSON.valueToTree(actual), expected);
            }
            if (expected.isNumber()) {
                if (!(actual instanceof Number)) {
                    return false;
                }
                Number number = (Number) actual;
                if (actual instanceof Float) {
                    return number.floatValue() == (float) expected.asDouble();
                }
                return expected.isIntegralNumber() && !(actual instanceof Double)
                        ? number.longValue() == expected.asLong()
                        : number.doubleValue() == expected.asDouble();
            }
            if (expected.isBoolean()) {
                return actual.equals(expected.asBoolean());
            }
            if (expected.isTextual()) {
                return (actual instanceof String || actual instanceof Character)
                        && actual.toString().equals(expected.asText());
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

        // ---------------------------------------------------------------------------------------- validation

        /** Validates an object and compares the issues, code and path in reported order, with the expected ones. */
        void checkIssues(String path, JsonNode expected) {
            ValidationReport report = vobject(resolve(path)).vmf().content().validate();
            List<String> actual = report.getIssues().stream()
                    .map(issue -> issue.getCode() + " " + issue.getPath()).collect(Collectors.toList());
            List<String> wanted = new ArrayList<>();
            expected.forEach(issue -> wanted.add(issue.get("code").asText() + " " + issue.get("path").asText()));
            if (!actual.equals(wanted)) {
                throw new ScenarioFailure(path + ": expected issues " + wanted + ", found " + actual);
            }
            if (report.isValid() != wanted.isEmpty()) {
                throw new ScenarioFailure(path + ": the report is " + (report.isValid() ? "valid" : "invalid")
                        + " with the issues " + actual);
            }
        }

        // ---------------------------------------------------------------------------------------- events

        void listen(String path, String name, String scope, JsonNode node) {
            List<Event> received = events.computeIfAbsent(name, key -> new ArrayList<>());
            List<Object[]> log = node.has("log") ? calls.computeIfAbsent(node.get("log").asText(),
                    key -> new ArrayList<>()) : null;
            JsonNode react = node.get("react");
            Subscription subscription = vobject(resolve(path)).vmf().changes().addListener(change -> {
                for (Event event : toEvents(change)) {
                    received.add(event);
                    if (log != null) {
                        log.add(new Object[]{name, event});
                    }
                    if (react != null && matches(react.get("when"), event)) {
                        // the listener edits the model while the change is delivered
                        JsonNode edit = react.get("do");
                        try {
                            execute(operation(edit), edit);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            }, !"local".equals(scope));
            subscriptions.put(name, subscription);
        }

        void checkCalls(String name, JsonNode expected) {
            List<Object[]> log = calls.computeIfAbsent(name, key -> new ArrayList<>());
            List<Object[]> received = new ArrayList<>(log);
            log.clear();
            StringBuilder rendered = new StringBuilder("[");
            for (Object[] call : received) {
                Event event = (Event) call[1];
                rendered.append(rendered.length() > 1 ? ", " : "").append(call[0]).append(' ')
                        .append(event.property).append(' ').append(render(event.oldValue)).append("->")
                        .append(render(event.newValue));
            }
            rendered.append(']');
            boolean same = received.size() == expected.size();
            for (int i = 0; same && i < received.size(); i++) {
                JsonNode wanted = expected.get(i);
                same = wanted.get("listener").asText().equals(received.get(i)[0])
                        && matches(wanted, (Event) received.get(i)[1]);
            }
            if (!same) {
                throw new ScenarioFailure(name + ": expected " + expected + ", received " + rendered);
            }
        }

        /**
         * A set change becomes an add and a remove event of its members; a map change becomes per-key events:
         * removed keys, replaced keys (with the new values) and added keys.
         */
        List<Event> toEvents(Change change) {
            if (change.setChange().isPresent()) {
                var set = change.setChange().get();
                List<Event> events = new ArrayList<>();
                if (!set.removed().isEmpty()) {
                    events.add(new Event(step, change.object(), change.propertyName(), "remove", null, null,
                            new ArrayList<>(set.removed()), List.of()));
                }
                if (!set.added().isEmpty()) {
                    events.add(new Event(step, change.object(), change.propertyName(), "add", null, null,
                            new ArrayList<>(set.added()), List.of()));
                }
                return events;
            }
            if (change.mapChange().isPresent()) {
                Map<Object, Object> removed = change.mapChange().get().removed().entries();
                Map<Object, Object> added = change.mapChange().get().added().entries();
                List<Event> events = new ArrayList<>();
                List<Object> keys = new ArrayList<>();
                List<Object> values = new ArrayList<>();
                removed.forEach((key, value) -> {
                    if (!added.containsKey(key)) {
                        keys.add(key);
                        values.add(value);
                    }
                });
                mapEvent(change, "remove", keys, values, events);
                added.forEach((key, value) -> {
                    if (removed.containsKey(key)) {
                        keys.add(key);
                        values.add(value);
                    }
                });
                mapEvent(change, "replace", keys, values, events);
                added.forEach((key, value) -> {
                    if (!removed.containsKey(key)) {
                        keys.add(key);
                        values.add(value);
                    }
                });
                mapEvent(change, "add", keys, values, events);
                return events;
            }
            if (change.referenceSetChange().isPresent()) {
                var set = change.referenceSetChange().get();
                List<Event> events = new ArrayList<>();
                if (!set.removed().isEmpty()) {
                    events.add(new Event(step, change.object(), change.propertyName(), "remove", null, null,
                            new ArrayList<>(set.removed()), set.added().isEmpty() ? set.indices() : List.of()));
                }
                if (!set.added().isEmpty()) {
                    events.add(new Event(step, change.object(), change.propertyName(), "add", null, null,
                            new ArrayList<>(set.added()), set.indices()));
                }
                return events;
            }
            return List.of(toEvent(change));
        }

        void mapEvent(Change change, String kind, List<Object> keys, List<Object> values, List<Event> events) {
            if (!keys.isEmpty()) {
                events.add(new Event(step, change.object(), change.propertyName(), kind, null, null,
                        new ArrayList<>(values), List.of(), new ArrayList<>(keys)));
                keys.clear();
                values.clear();
            }
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
                    List<Object> keys = new ArrayList<>(last.keys);
                    keys.addAll(event.keys);
                    result.set(result.size() - 1, new Event(last.step, last.source, last.property, last.kind,
                            null, null, elements, indices, keys));
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
            if (wanted.has("keys") && !sameScalar(event.keys, wanted.get("keys"))) return false;
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
                        : " " + render(event.elements) + "@" + (event.keys.isEmpty() ? event.indices
                                : render(event.keys))) + "}";
            }
            if (value instanceof Collection || value instanceof RefSet) {
                return members(value).stream().map(this::render).collect(Collectors.joining(", ", "[", "]"));
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
                    if (value instanceof Collection || value instanceof RefSet) {
                        text.append('[');
                        for (Object element : members(value)) {
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
