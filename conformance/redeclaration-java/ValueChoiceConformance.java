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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ValueChoiceConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "value-choices"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject node) { return node.vmf().reflect().type().getName(); }
        @Override public String type(VObject node) { return id(node); }
        @Override public VObject create(String type, String id) { throw new IllegalArgumentException("unexpected creation"); }
    };

    private ValueChoiceConformance() { }

    public static void verify(boolean writing, Path directory) throws IOException {
        var baseline = specimen();
        var edited = specimen();
        var journal = JournalRecorder.recordFrame(edited, BINDING, "initial", 0, ValueChoiceConformance::edit);
        assertEdited(edited, false);
        var detached = ObjectOption.newBuilder().withValue(OptionObject.newInstance()).build();
        var noRefs = mapper(VMFDocumentFormat.JSON, false);
        check(!noRefs.readTree(noRefs.writeValueAsString(detached)).has("value"), "Unowned references are omitted by value");
        for (var format : VMFDocumentFormat.values()) {
            var mapper = mapper(format, true);
            String prefix = (writing ? "java-" : "csharp-") + "value-choices";
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            Path checkpoint = directory.resolve(prefix + "-checkpoint" + suffix);
            Path snapshot = directory.resolve(prefix + "-snapshot" + suffix);
            Path log = directory.resolve(prefix + "-journal" + suffix);
            if (writing) {
                Files.writeString(checkpoint, mapper.writeValueAsString(baseline));
                Files.writeString(snapshot, mapper.writeValueAsString(edited));
                Files.writeString(log, VMFJournalCodec.write(journal, format));
            }
            assertEdited(mapper.readValue(snapshot.toFile(), OptionDocument.class), format == VMFDocumentFormat.XML);
            var restored = mapper.readValue(checkpoint.toFile(), OptionDocument.class);
            var actualLog = VMFJournalCodec.read(Files.readString(log), format);
            check(actualLog.wireVersion() == 1 && VMFJournalCodec.write(journal, VMFDocumentFormat.JSON)
                    .equals(VMFJournalCodec.write(actualLog, VMFDocumentFormat.JSON)), "Same existing V1 operation kinds and fields");
            assertEdited(JournalReplayer.replay(restored, BINDING, "initial", actualLog), false);
            check(restored.getOptions().get(3).getValue() == null, "Replay preserves the baseline");
        }
        verifyUntypedObjectContent();
        System.out.println("Java verified typed JSON-like value branches, aliases and V1 interchange in JSON/YAML/XML.");
    }

    private static void verifyUntypedObjectContent() throws IOException {
        String left = "{\"value\":{\"items\":[1,null,-0.0,9007199254740993],\"flag\":true}}";
        String right = "{\"value\":{\"flag\":true,\"items\":[1,null,-0.0,9007199254740993]}}";
        for (boolean references : List.of(false, true)) {
            var mapper = mapper(VMFDocumentFormat.JSON, references);
            var first = mapper.readValue(left, NullOption.class);
            var second = mapper.readValue(right, NullOption.class);
            var firstView = first.asReadOnly();
            var secondView = second.asReadOnly();
            check(first.getValue() != second.getValue() && first.vmf().content().equals(second)
                            && second.vmf().content().equals(first) && firstView.vmf().content().equals(secondView)
                            && first.vmf().content().hashCode() == secondView.vmf().content().hashCode(),
                    "Separate Object map/list values compare by contents");
            for (String different : List.of(right.replace("-0.0", "0.0"), right.replace("[1,", "[1.0,"),
                    right.replace("1,null", "null,1"), right.replace("9007199254740993", "9007199254740992"))) {
                check(!firstView.vmf().content().equals(mapper.readValue(different, NullOption.class)),
                        "Untyped collection equality retains list order and boxed numbers");
            }
        }
    }

    private static OptionDocument specimen() {
        return OptionDocument.newBuilder().withObjectTarget(OptionObject.newBuilder().withLabel("object").build())
                .withArrayTarget(OptionArray.newBuilder().withLabels("a", "b").build())
                .withOptions(TextOption.newBuilder().withValue("old").build(),
                        NumberOption.newBuilder().withValue(1.0).build(), FlagOption.newBuilder().withValue(false).build(),
                        ObjectOption.newInstance(), ArrayOption.newInstance(), NullOption.newBuilder().withValue("initial").build())
                .build();
    }

    private static void edit(OptionDocument root) {
        ((TextOption) root.getOptions().get(0)).setValue("new");
        ((NumberOption) root.getOptions().get(1)).setValue(-0.0);
        ((FlagOption) root.getOptions().get(2)).setValue(true);
        ((ObjectOption) root.getOptions().get(3)).setValue(root.getObjectTarget());
        ((ArrayOption) root.getOptions().get(4)).setValue(root.getArrayTarget());
        ((NullOption) root.getOptions().get(5)).setValue(false);
    }

    private static void assertEdited(OptionDocument root, boolean xml) {
        var options = root.getOptions();
        check(Objects.equals("new", options.get(0).getValue()) && Objects.equals(-0.0, options.get(1).getValue())
                && Objects.equals(true, options.get(2).getValue()), "The common getter observes concrete scalar types");
        check(options.get(3).getValue() == root.getObjectTarget() && options.get(4).getValue() == root.getArrayTarget(),
                "The same getter observes actual model reference identities");
        check(Objects.equals(xml ? "false" : Boolean.FALSE, options.get(5).getValue()), "Writable Object branch follows its original decoder");
        Class<?>[] types = { String.class, Double.class, Boolean.class, OptionObject.class, OptionArray.class, Object.class };
        for (int index = 0; index < types.length; index++) {
            var fields = options.get(index).vmf().reflect().properties().stream().filter(p -> p.getName().equals("value"))
                    .collect(java.util.stream.Collectors.toList());
            check(fields.size() == 1 && fields.get(0).getType().getName().equals(types[index].getName()),
                    "One reflected field with the concrete type");
        }
        var view = root.asReadOnly();
        check(view.getOptions().get(3).getValue() == view.getObjectTarget()
                && view.getOptions().get(4).getValue() == view.getArrayTarget(), "Common readonly getter projects model targets");
    }

    private static ObjectMapper mapper(VMFDocumentFormat format, boolean refs) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(refs ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
