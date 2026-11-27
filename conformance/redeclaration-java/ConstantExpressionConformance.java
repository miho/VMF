package eu.mihosoft.vmf.conformance.redeclarations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ConstantExpressionConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "constant-expressions"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject node) { return "root"; }
        @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalArgumentException("unexpected creation"); }
    };

    private ConstantExpressionConformance() { }

    public static void verify(boolean writing, Path directory) throws IOException {
        verify(writing, directory, "int", IntegerExpression.class, IntegerExpression::newInstance,
                7, expression -> expression.setValue(7));
        verify(writing, directory, "double", FloatingExpression.class, FloatingExpression::newInstance,
                -0.0, expression -> expression.setValue(-0.0));
        verify(writing, directory, "bool", BooleanExpression.class, BooleanExpression::newInstance,
                true, expression -> expression.setValue(true));
        verify(writing, directory, "string", StringExpression.class, StringExpression::newInstance,
                "literal", expression -> expression.setValue("literal"));
        System.out.println("Java verified the concrete scalar storage and V1 interchange of passive expression getters.");
    }

    private static <T extends ConstExpression> void verify(boolean writing, Path directory, String name,
            Class<T> type, Supplier<T> create, Object expected, Consumer<T> edit) throws IOException {
        T root = create.get();
        var field = root.vmf().reflect().propertyByName("value").orElseThrow();
        check(field.getType().getName().equals(expected.getClass().getName()) && field.getDefault() == null
                && root.vmf().reflect().properties().stream().filter(p -> p.getName().equals("value")).count() == 1,
                "One reflected scalar property with a null default");
        ConstExpression trait = root;
        var view = root.asReadOnly();
        check(trait.getValue() == null && view.getValue() == null, "Default visible on both contracts");
        var expectedJournal = JournalRecorder.recordFrame(root, BINDING, "initial", 0, edit);
        check(Objects.equals(trait.getValue(), expected) && Objects.equals(view.getValue(), expected),
                "Both contracts observe the concrete scalar");
        for (var format : VMFDocumentFormat.values()) {
            var mapper = mapper(format);
            String prefix = (writing ? "java-" : "csharp-") + "constant-expression-" + name;
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            Path checkpoint = directory.resolve(prefix + "-checkpoint" + suffix);
            Path snapshot = directory.resolve(prefix + "-snapshot" + suffix);
            Path log = directory.resolve(prefix + "-journal" + suffix);
            if (writing) {
                Files.writeString(checkpoint, mapper.writeValueAsString(create.get()));
                Files.writeString(snapshot, mapper.writeValueAsString(root));
                Files.writeString(log, VMFJournalCodec.write(expectedJournal, format));
            }
            T restored = mapper.readValue(snapshot.toFile(), type);
            check(Objects.equals(expected, restored.getValue()), "Concrete scalar snapshot value");
            T baseline = mapper.readValue(checkpoint.toFile(), type);
            var journal = VMFJournalCodec.read(Files.readString(log), format);
            check(journal.wireVersion() == 1 && VMFJournalCodec.write(expectedJournal, VMFDocumentFormat.JSON)
                    .equals(VMFJournalCodec.write(journal, VMFDocumentFormat.JSON)), "An unchanged typed V1 journal");
            var replayed = JournalReplayer.replay(baseline, BINDING, "initial", journal);
            check(Objects.equals(expected, replayed.getValue()) && baseline.getValue() == null,
                    "Typed replay on a fresh checkpoint");
        }
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
