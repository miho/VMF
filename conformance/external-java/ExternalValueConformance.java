package eu.mihosoft.vmf.conformance.externalcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.external.BoundedExternalIds;
import eu.mihosoft.vmf.conformance.external.ExternalValueEntry;
import eu.mihosoft.vmf.conformance.external.ExternalSamples;
import eu.mihosoft.vmf.conformance.external.GenericExternalIds;
import eu.mihosoft.vmf.conformance.external.ReadOnlyExternalSamples;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class ExternalValueConformance {
    private static final String MODEL = "conformance.external-journal";
    private static final String CHECKPOINT = "external-checkpoint";
    private static final String ENTRY_TYPE = "external-entry";
    private static final String CODEC = "java.uuid/v1";
    private static final UUID FIRST = UUID.fromString("980e13c6-84a0-49d6-821d-737269635aef");
    private static final UUID SECOND = UUID.fromString("b0e36ead-e4e3-4de7-bca4-b2ba2a7c6035");
    private static final UUID THIRD = UUID.fromString("7bfefa79-00d2-44b6-823a-b7c29e8d2246");
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final List<VMFDocumentFormat> FORMATS =
            List.of(VMFDocumentFormat.JSON, VMFDocumentFormat.XML, VMFDocumentFormat.YAML);
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return ((ExternalValueEntry) object).getId(); }
        @Override public String type(VObject object) { return ENTRY_TYPE; }
        @Override public VObject create(String type, String id) {
            check(type.equals(ENTRY_TYPE), "registered creation type");
            return ExternalValueEntry.newBuilder().withId(id).build();
        }
    };

    private static final String BOUNDED_MODEL = "conformance.bounded-external-ids";
    private static final String BOUNDED_CHECKPOINT = "ids-start";
    private static final JournalBinding BOUNDED_BINDING = new JournalBinding() {
        @Override public String modelId() { return BOUNDED_MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return "root"; }
        @Override public String type(VObject object) { return BoundedExternalIds.class.getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No created values"); }
    };

    private ExternalValueConformance() {}

    public static void main(String[] args) throws Exception {
        var directory = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(directory);
            var start = checkpoint();
            REFERENCES.writeValue(directory.resolve("java-checkpoint.json").toFile(), start);
            var journal = record(start);
            for (var format : FORMATS) {
                String wire = VMFJournalCodec.write(journal, format);
                Files.writeString(journalPath(directory, "java", format), wire);
                verify(start, VMFJournalCodec.read(wire, format));
            }
            checkRejectedValues(start);
            verifyBoundedIds(directory, "java", true);
            verifyGenericIds(directory, true);
            System.out.println("Java recorded and replayed UUID values in JSON, XML, and YAML journals.");
        } else if (args[0].equals("read")) {
            var start = REFERENCES.readValue(directory.resolve("csharp-checkpoint.json").toFile(), ExternalValueEntry.class);
            for (var format : FORMATS) {
                verify(start, VMFJournalCodec.read(Files.readString(journalPath(directory, "csharp", format)), format));
            }
            verifyBoundedIds(directory, "csharp", false);
            verifyGenericIds(directory, false);
            System.out.println("Java replayed the C# UUID journals from a fresh checkpoint.");
        } else {
            throw new IllegalArgumentException("Expected write or read");
        }
    }

    private static void verifyBoundedIds(Path directory, String author, boolean write) throws Exception {
        var validator = ModelValidator.builder().build();
        var baselineFile = directory.resolve(author + "-bounded-checkpoint.json");
        var journalFile = directory.resolve(author + "-bounded-journal.json");
        var json = plainMapper(VMFDocumentFormat.JSON);
        ChangeJournal journal = null;
        BoundedExternalIds value = null;
        if (write) {
            value = BoundedExternalIds.newInstance();
            check(!validator.validate(value).isValid(), "Empty list is below its lower bound");
            value.getIds().add(null);
            check(validator.validate(value).isValid(), "A null external list slot counts toward the lower bound");
            json.writeValue(baselineFile.toFile(), value);
            journal = JournalRecorder.recordFrame(value, BOUNDED_BINDING, BOUNDED_CHECKPOINT, 0,
                    model -> model.getIds().addAll(List.of(FIRST, FIRST)));
            Files.writeString(journalFile, VMFJournalCodec.write(journal));
            var schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .generateSchemaAsString(BoundedExternalIds.class);
            var ids = json.readTree(schema).path("properties").path("ids");
            check(ids.path("minItems").asInt() == 1 && ids.path("maxItems").asInt() == 2,
                    "Schema retains both declared bounds");
        }

        for (var format : FORMATS) {
            var mapper = plainMapper(format);
            var file = directory.resolve(author + "-bounded-ids." + format.name().toLowerCase(Locale.ROOT)).toFile();
            if (write) mapper.writeValue(file, value);
            checkBoundedIds(mapper.readValue(file, BoundedExternalIds.class), validator);
        }
        var checkpoint = json.readValue(baselineFile.toFile(), BoundedExternalIds.class);
        if (!write) journal = VMFJournalCodec.read(Files.readString(journalFile));
        check(journal.wireVersion() == 1 && validator.validate(checkpoint).isValid(), "Valid baseline and V1 envelope");
        checkBoundedIds(JournalReplayer.replay(checkpoint, BOUNDED_BINDING, BOUNDED_CHECKPOINT, journal), validator);
        check(checkpoint.getIds().size() == 1 && checkpoint.getIds().get(0) == null, "Replay preserves checkpoint");
    }

    private static void checkBoundedIds(BoundedExternalIds value, ModelValidator validator) {
        check(value.getIds().equals(Arrays.asList(null, FIRST, FIRST)), "Snapshot/replay retains nullable duplicate IDs");
        var report = validator.validate(value);
        check(report.getIssues().size() == 1 && report.getIssues().get(0).getPath().equals("/ids"),
                "Only validation rejects excess list slots");
    }

    private static void verifyGenericIds(Path directory, boolean write) throws Exception {
        var validator = ModelValidator.builder().build();
        var source = GenericExternalIds.newBuilder().withLatest(FIRST).withSamples(SECOND, null, FIRST, FIRST).build();
        ExternalSamples<UUID> typed = source;
        ReadOnlyExternalSamples<UUID> view = typed.asReadOnly();
        typed.setLatest(null);
        check(view.getLatest() == null && view.getSamples().get(1) == null, "generic UUID live view");
        typed.setLatest(FIRST);
        var argument = GenericExternalIds.type().superTypes().get(0).getDescriptor().getArguments().get(0);
        check(argument.getName().equals("java.util.UUID"), "generic Java UUID descriptor");
        var empty = validator.validate(GenericExternalIds.newInstance());
        check(empty.getIssues().size() == 1 && empty.getIssues().get(0).getPath().equals("/samples"),
                "generic UUID collection validation");

        for (var format : FORMATS) {
            for (boolean references : List.of(false, true)) {
                var mapper = snapshotMapper(format, references);
                var filename = (write ? "java" : "csharp") + "-generic-ids-" + references
                        + "." + format.name().toLowerCase(Locale.ROOT);
                var file = directory.resolve(filename).toFile();
                if (write) mapper.writeValue(file, source);
                var model = mapper.readValue(file, GenericExternalIds.class);
                check(FIRST.equals(model.getLatest()) && model.getSamples().equals(Arrays.asList(SECOND, null, FIRST, FIRST)),
                        "closed generic UUIDs retain value, null and order");
                check(validator.validate(model).isValid(), "generic UUID snapshot satisfies the lower bound");
            }
        }
    }

    private static ObjectMapper plainMapper(VMFDocumentFormat format) {
        return snapshotMapper(format, false);
    }

    private static ObjectMapper snapshotMapper(VMFDocumentFormat format, boolean references) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static Path journalPath(Path directory, String source, VMFDocumentFormat format) {
        return directory.resolve(source + "-journal." + format.name().toLowerCase(Locale.ROOT));
    }

    private static ExternalValueEntry checkpoint() {
        var start = ExternalValueEntry.newBuilder().withId("root").withSourceId(FIRST).build();
        start.getRelatedIds().addAll(Arrays.asList(SECOND, null, FIRST));
        return start;
    }

    private static ChangeJournal record(ExternalValueEntry start) {
        var work = start.clone();
        try (var recorder = JournalRecorder.start(work, BINDING, CHECKPOINT, 40)) {
            work.setSourceId(SECOND);
            work.getRelatedIds().set(1, THIRD);
            check(recorder.publishFrame().orElseThrow().sequence() == 41, "first journal frame sequence");
            var child = ExternalValueEntry.newBuilder().withId("child").withSourceId(THIRD).build();
            child.getRelatedIds().addAll(Arrays.asList(FIRST, null, THIRD));
            work.getChildren().add(child);
            return recorder.finish();
        }
    }

    private static void verify(ExternalValueEntry start, ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 2, "Version 1 journal envelope");
        var frames = journal.transactions();
        var first = frames.get(0).operations().get(0);
        check(first.before().equals(List.of(uuid(FIRST))) && first.after().equals(List.of(uuid(SECOND))),
                "concrete UUID values encode as external values");
        var creation = frames.get(1).operations().get(0).created().get(0);
        check(creation.type().equals(ENTRY_TYPE) && creation.properties().get("sourceId").equals(List.of(uuid(THIRD))),
                "external values in initial creation fields");
        var replayed = JournalReplayer.replay(start, BINDING, CHECKPOINT, journal);
        check(replayed.getSourceId().equals(SECOND) && replayed.getRelatedIds().equals(List.of(SECOND, THIRD, FIRST)),
                "replayed root UUID values");
        check(replayed.getChildren().size() == 1, "replayed child creation");
        var child = replayed.getChildren().get(0);
        check(child.getId().equals("child") && child.getSourceId().equals(THIRD)
                && child.getRelatedIds().equals(Arrays.asList(FIRST, null, THIRD)), "replayed child UUID values");
        check(start.getSourceId().equals(FIRST) && start.getRelatedIds().equals(Arrays.asList(SECOND, null, FIRST))
                && start.getChildren().isEmpty(), "replay leaves the checkpoint independent");
    }

    private static void checkRejectedValues(ExternalValueEntry start) {
        for (var value : List.of(Value.external("example.unknown/v1", SECOND.toString()),
                Value.external(CODEC, SECOND.toString().toUpperCase(Locale.ROOT)),
                Value.external(CODEC, "1-1-1-1-1"))) {
            var edit = new Operation(Kind.SET, "root", "sourceId", List.of(),
                    List.of(uuid(FIRST)), List.of(value), List.of());
            var journal = new ChangeJournal(MODEL, 1, "root", CHECKPOINT, 40, List.of(new Frame(41, List.of(edit))));
            try {
                JournalReplayer.replay(start, BINDING, CHECKPOINT, journal);
            } catch (IllegalArgumentException expected) {
                continue;
            }
            throw new AssertionError("Invalid UUID value was accepted");
        }
    }

    private static Value uuid(UUID value) { return Value.external(CODEC, value.toString()); }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
