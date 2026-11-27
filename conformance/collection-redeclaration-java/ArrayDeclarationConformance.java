package eu.mihosoft.vmf.conformance.collectionredeclarations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Multiplicity;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public final class ArrayDeclarationConformance {
    private static final List<String> SIZES = Arrays.asList("4", null, "4");
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "mini-clang-array-shapes"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject node) { return "root"; }
        @Override public String type(VObject node) { return node.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalArgumentException("Unexpected creation"); }
    };

    private ArrayDeclarationConformance() { }

    public static void verify(boolean write, Path directory) throws IOException {
        Files.createDirectories(directory);
        verify(ArrayDeclStatement.class, ArrayDeclStatement::newInstance, "array", write, directory);
        verify(Parameter.class, Parameter::newInstance, "parameter", write, directory);
        System.out.println("Verified both " + (write ? "Java" : "C#") + " declaration orders, mutable and read-only aliases, reflection, and JSON/YAML/XML snapshots and V1 replay.");
    }

    private static <T extends DeclStatement> void verify(Class<T> type, Supplier<T> create, String name, boolean write, Path directory)
            throws IOException {
        T graph = create.get();
        var concreteSizes = ((WithArraySizes) graph).getArraySizes();
        check(graph.getArraySizes() == concreteSizes, "Both writable supertypes share the one list");
        var readOnly = graph.asReadOnly();
        var readOnlySizes = ((ReadOnlyDeclStatement) readOnly).getArraySizes();
        check(readOnlySizes == ((ReadOnlyWithArraySizes) readOnly).getArraySizes(), "Read-only supertypes share the view");
        concreteSizes.addAll(SIZES);
        check(SIZES.equals(new ArrayList<>(readOnlySizes)), "The live view preserves nullable and duplicate entries");
        try {
            readOnlySizes.add("unwanted");
            throw new AssertionError("Read-only view accepted mutation");
        } catch (UnsupportedOperationException expected) { }
        var property = graph.vmf().reflect().propertyByName("arraySizes").orElseThrow();
        check(property.get() == concreteSizes && property.getMultiplicity().equals(Multiplicity.of(0, -1, true, false))
                && graph.vmf().reflect().properties().stream().filter(p -> p.getName().equals("arraySizes")).count() == 1,
                "One reflected ordered nonunique unbounded property");

        var expected = JournalRecorder.recordFrame(create.get(), BINDING, "initial", 0,
                owner -> owner.getArraySizes().addAll(SIZES));
        for (var format : VMFDocumentFormat.values()) {
            var mapper = mapper(format);
            String prefix = (write ? "java-" : "csharp-") + "array-declarations-" + name;
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            Path checkpoint = directory.resolve(prefix + "-checkpoint" + suffix);
            Path snapshot = directory.resolve(prefix + "-snapshot" + suffix);
            Path journal = directory.resolve(prefix + "-journal" + suffix);
            if (write) {
                Files.writeString(checkpoint, mapper.writeValueAsString(create.get()));
                Files.writeString(snapshot, mapper.writeValueAsString(graph));
                Files.writeString(journal, VMFJournalCodec.write(expected, format));
            }
            T baseline = mapper.readValue(checkpoint.toFile(), type);
            T restored = mapper.readValue(snapshot.toFile(), type);
            check(SIZES.equals(new ArrayList<>(restored.getArraySizes())), "Snapshot preserved one list and all slots: " + name + " " + format);
            var recorded = VMFJournalCodec.read(Files.readString(journal), format);
            check(recorded.wireVersion() == 1 && VMFJournalCodec.write(expected, VMFDocumentFormat.JSON)
                .equals(VMFJournalCodec.write(recorded, VMFDocumentFormat.JSON)), "Journal matches Java V1");
            var replayed = JournalReplayer.replay(baseline, BINDING, "initial", recorded);
            check(baseline.getArraySizes().isEmpty() && SIZES.equals(new ArrayList<>(replayed.getArraySizes())), "Replay preserved positions");
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
