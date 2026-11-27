package eu.mihosoft.vmf.conformance.collectiondefaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class CollectionDefaultConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));

    private CollectionDefaultConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            verifyLive();
            verifyBuilderCopies();
            var file = exchange.resolve("java-collection-defaults.json");
            MAPPER.writeValue(file.toFile(), specimen());
            verifyWire(file);
            verifyLiteral(exchange.resolve("java-literal-defaults.json"), true);
            verifyLiteralSchema(exchange.resolve("java-literal-defaults-schema.json"), true);
            System.out.println("Java verified managed collection default construction, reflection, copies and explicit document values.");
        } else {
            verifyWire(exchange.resolve("csharp-collection-defaults.json"));
            verifyLiteral(exchange.resolve("csharp-literal-defaults.json"), false);
            verifyLiteralSchema(exchange.resolve("csharp-literal-defaults-schema.json"), false);
            System.out.println("Java restored C# explicit managed collection values and executable defaults.");
        }
    }

    private static void verifyBuilderCopies() {
        var source = ManagedSamples.newInstance();
        source.getNumbers().clear();
        source.getNumbers().add(7);
        source.getLabels().clear();
        source.getLabels().add("published");
        source.getCodes().clear();
        source.getCodes().add(19L);
        source.getOptions().clear();
        source.getOptions().put("known", 12);

        var target = ManagedSamples.newInstance();
        var numbers = target.getNumbers();
        numbers.clear();
        target.getLabels().clear();
        target.getLabels().add("existing");
        target.getCodes().clear();
        target.getCodes().add(31L);
        target.getOptions().clear();
        target.getOptions().put("existing", null);
        target.getOptions().put("known", 42);
        int before = CollectionDefaults.numberCalls;
        var builder = ManagedSamples.newBuilder();
        check(CollectionDefaults.numberCalls == before + 1, "Builder stages the executable fallback once");
        CollectionDefaults.numbers.set(0, 8);
        try {
            check(builder.applyFrom(source).applyTo(target) == builder, "Applying retains the staged builder");
            check(numbers == target.getNumbers() && numbers.equals(List.of(3, 5, 7))
                            && target.getLabels().equals(List.of("existing", "draft", "published"))
                            && target.getCodes().equals(java.util.Set.of(19L, 23L, 31L))
                            && List.copyOf(target.getOptions().keySet()).equals(List.of("existing", "known", "present"))
                            && target.getOptions().get("known") == 12 && target.getOptions().get("existing") == null
                            && target.getOptions().get("present") == null,
                    "Append merges input with captured list, set, and map fallbacks before applying to live storage");

            source.getNumbers().clear();
            source.getLabels().clear();
            source.getCodes().clear();
            source.getOptions().clear();
            builder.appendCollections(false).applyFrom(source);
            source.getNumbers().add(9);
            builder.applyTo(target);
            check(numbers == target.getNumbers() && numbers.isEmpty() && target.getLabels().isEmpty()
                            && target.getCodes().isEmpty() && target.getOptions().isEmpty(),
                    "Replace keeps explicit empty inputs and isolates them from later source mutators");
            check(CollectionDefaults.numberCalls == before + 1, "Copy operations do not call the evaluator");

            var result = builder.build();
            check(result.getNumbers().isEmpty() && result.getOptions().isEmpty()
                            && CollectionDefaults.numberCalls == before + 2,
                    "Build constructs fresh defaults, then applies the staged empty values");
        } finally {
            CollectionDefaults.numbers.set(0, 3);
        }
    }

    private static void verifyLiteral(Path path, boolean write) throws IOException {
        var seed = MAPPER.readValue("{}", LiteralSamples.class);
        check(seed.getStages().equals(List.of("a,b", "c\\d", "e\"f")) && seed.getPorts().get("port") == 80,
                "The literal factory supplies the omitted fields with the declared values");
        if (write) MAPPER.writeValue(path.toFile(), LiteralSamples.newBuilder().withStages(List.of()).build());
        var restored = MAPPER.readValue(path.toFile(), LiteralSamples.class);
        check(restored.getStages().isEmpty() && restored.getPorts().get("port") == 80,
                "An explicitly empty field can replace a literal default");
    }

    private static void verifyLiteralSchema(Path path, boolean write) throws IOException {
        if (write) {
            var generator = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL);
            Files.writeString(path, generator.generateSchemaAsString(SchemaLiteralSamples.class));
        }
        var schema = MAPPER.readTree(path.toFile());
        check(schema.path("required").equals(MAPPER.readTree("[\"needsWire\"]")),
                "Only the field with an insufficient default must appear in the document");
        var properties = schema.path("properties");
        check(properties.path("values").path("default").equals(MAPPER.readTree("[7,11]"))
                        && properties.path("codes").path("default").equals(MAPPER.readTree("[2]"))
                        && properties.path("choices").path("default").equals(MAPPER.readTree("[{\"key\":4,\"value\":\"ready\"}]"))
                        && properties.path("needsWire").path("default").equals(MAPPER.readTree("[]")),
                "Schema defaults use list, set and typed-map wire shapes");
        var value = MAPPER.readValue("{\"needsWire\":[5]}", SchemaLiteralSamples.class);
        check(ModelValidator.builder().build().validate(value).isValid()
                        && value.getValues().equals(List.of(7, 11)) && value.getCodes().contains(2L)
                        && value.getChoices().get(4).equals("ready"),
                "Reader validates a document whose omitted fields have valid defaults");
    }

    private static DefaultCollections specimen() {
        var mutable = ManagedSamples.newBuilder().withNumbers(17).withLabels(List.of())
                .withCodes(31L).withOptions(Map.of()).build();
        var frozen = FrozenSamples.newBuilder().withNumbers(List.of()).withLabels("closed")
                .withCodes(List.of()).build();
        return DefaultCollections.newBuilder().withMutable(mutable).withFrozen(frozen).build();
    }

    private static void verifyLive() {
        int before = CollectionDefaults.numberCalls;
        var model = ManagedSamples.newInstance();
        check(CollectionDefaults.numberCalls == before + 1 && model.getNumbers().equals(List.of(3, 5))
                && model.getLabels().equals(List.of("draft", "published")) && model.getCodes().contains(19L)
                && model.getOptions().containsKey("present") && model.getOptions().get("present") == null,
                "Construction evaluates each managed default and preserves typed values");
        var field = field(model, "numbers");
        var live = model.getNumbers();
        Object oldDefault = field.getDefault();
        check(oldDefault != field.getDefault() && oldDefault.equals(List.of(3, 5)), "Each reflection query snapshots a fresh fallback");
        reject(UnsupportedOperationException.class, () -> ((Collection<?>) oldDefault).clear());
        CollectionDefaults.numbers.set(0, 8);
        try {
            check(live.equals(List.of(3, 5)) && field.isSet() && field(model.asReadOnly(), "numbers").getDefault().equals(List.of(8, 5)),
                    "Live storage is defensively copied while reflected defaults are recomputed");
            var callbacks = new ArrayList<String>();
            var watch = model.vmf().changes().addPropertyListener("numbers", edit -> callbacks.add(edit.propertyName()));
            try {
                field.unset();
                check(live == model.getNumbers() && live.equals(List.of(8, 5)) && callbacks.equals(List.of("numbers")),
                        "Same-size reset edits the same list with one live change");
            } finally {
                watch.unsubscribe();
            }
        } finally {
            CollectionDefaults.numbers.set(0, 3);
        }
        CollectionDefaults.labels.add("draft");
        try {
            reject(IllegalArgumentException.class, field(model, "labels")::unset);
            check(model.getLabels().equals(List.of("draft", "published")), "Invalid default is rejected before replacement");
        } finally {
            CollectionDefaults.labels.remove(2);
        }

        model.getOptions().clear();
        model.getOptions().put("known", 11);
        model.getOptions().put("present", null);
        field.setDefault(List.of(13));
        before = CollectionDefaults.numberCalls;
        ManagedSamples deep = model.vmf().content().deepCopy();
        ManagedSamples shallow = model.vmf().content().shallowCopy();
        check(CollectionDefaults.numberCalls == before + 2 && deep.getNumbers() != live && shallow.getNumbers() != live
                && deep.getNumbers().equals(List.of(8, 5)) && shallow.getNumbers().equals(List.of(8, 5))
                && field(deep, "numbers").getDefault().equals(List.of(13)) && field(shallow, "numbers").getDefault().equals(List.of(13))
                && List.copyOf(deep.getOptions().keySet()).equals(List.of("known", "present"))
                && List.copyOf(shallow.getOptions().keySet()).equals(List.of("known", "present")),
                "Copies seed and then replace their own managed buffers using the source values");

        before = CollectionDefaults.numberCalls;
        var staged = ManagedSamples.newBuilder();
        check(CollectionDefaults.numberCalls == before + 1, "Mutable builder captures a default at creation");
        var changed = staged.withNumbers(9).withOptions(Map.of()).build();
        check(CollectionDefaults.numberCalls == before + 2 && changed.getNumbers().equals(List.of(9)) && changed.getOptions().isEmpty(),
                "Mutable builder overrides the live defaults after constructing a result");

        before = CollectionDefaults.numberCalls;
        var frozen = FrozenSamples.newBuilder().withNumbers(8).build();
        check(CollectionDefaults.numberCalls == before + 1 && frozen.getNumbers().equals(List.of(8)),
                "Immutable builder evaluates at creation and retains explicit values");
        reject(UnsupportedOperationException.class, () -> frozen.getNumbers().add(7));
        reject(UnsupportedOperationException.class, () -> frozen.getOptions().put("added", 1));
    }

    private static void verifyWire(Path file) throws IOException {
        var document = MAPPER.readValue(file.toFile(), DefaultCollections.class);
        var mutable = document.getMutable();
        var frozen = document.getFrozen();
        check(mutable.getNumbers().equals(List.of(17)) && mutable.getLabels().isEmpty()
                && mutable.getCodes().contains(31L) && mutable.getOptions().isEmpty(), "Explicit mutable values replace constructor defaults");
        check(frozen.getNumbers().isEmpty() && frozen.getLabels().equals(List.of("closed")) && frozen.getCodes().isEmpty()
                && frozen.getOptions().containsKey("present") && frozen.getOptions().get("present") == null,
                "Explicit empty and present-null immutable fields round-trip");
        var absent = MAPPER.readValue("{}", ManagedSamples.class);
        var absentFrozen = MAPPER.readValue("{}", FrozenSamples.class);
        check(absent.getNumbers().equals(List.of(3, 5)) && absentFrozen.getNumbers().equals(List.of(3, 5)),
                "Absent fields receive the bound defaults in both mutability modes");
    }

    private static Property field(VObject model, String key) {
        return model.vmf().reflect().propertyByName(key).orElseThrow();
    }

    private static void reject(Class<? extends RuntimeException> expected, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            if (expected.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
