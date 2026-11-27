package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.conformance.values.ScalarSample;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.ChangesImpl;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Kind;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

final class JournalConformance {
    private static final String MODEL = "conformance.scalar-journal";
    private static final String ROOT = "scalar-sample";
    private static final String CHECKPOINT = "scalar-checkpoint-40";
    private static final long BASE_SEQUENCE = 40L;
    private static final long INITIAL_LONG = 9_007_199_254_740_993L;
    private static final String INSPECTION = "inspection \uD83D\uDD27";
    private static final String XML_INSPECTION = "inspection\u0001\rnext\nline\t";
    private static final double FINAL_PROBE = 0x1.23p45;
    private static final char SURROGATE = (char) 0xD800;

    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject object) { return type(object); }

        @Override public String type(VObject object) {
            if (object instanceof ScalarSample) return ROOT;
            throw new IllegalArgumentException("Unknown scalar journal object type");
        }

        @Override public VObject create(String type, String id) {
            if (type.equals(ROOT) && id.equals(ROOT)) return ScalarSample.newInstance();
            throw new IllegalArgumentException("Unknown scalar journal type or ID");
        }

        @Override public String propertyKey(VObject object, String name) {
            switch (name) {
                case "description": return "NAME";
                case "optionalDouble": return "probe";
                default: return name;
            }
        }

        @Override public String propertyName(VObject object, String key) {
            switch (key) {
                case "NAME": return "description";
                case "probe": return "optionalDouble";
                default: return key;
            }
        }
    };

    private JournalConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Usage: JournalConformance (write|read) <interchange-directory>");
        }
        var interchange = Path.of(args[1]);
        String producer = args[0].equals("write") ? "java" : "csharp";
        var checkpoint = interchange.resolve(producer + "-checkpoint.json");
        var journal = interchange.resolve(producer + "-journal.json");
        var control = interchange.resolve(producer + "-control-journal.xml");
        if (args[0].equals("write")) {
            Files.createDirectories(interchange);
            verifyRecordingOwnership();
            verifyManualVersioningOwnership();
            verifyExplicitPublication();
            verifyNativePublicationFailure();
            write(checkpoint, journal);
            var exactCheckpoint = PLAIN.readValue(checkpoint.toFile(), ScalarSample.class);
            var xml = JournalRecorder.recordFrame(exactCheckpoint, BINDING, CHECKPOINT, BASE_SEQUENCE,
                    sample -> sample.setDescription(XML_INSPECTION));
            Files.writeString(control, VMFJournalCodec.write(xml, VMFDocumentFormat.XML));
        }
        verify(checkpoint, journal);
        verifyControlXml(checkpoint, control);
        System.out.println("Java verified V1 scalar journals from " + (producer.equals("java") ? "Java" : "C#") + " in JSON and XML.");
    }

    @SuppressWarnings("deprecation")
    private static void verifyManualVersioningOwnership() {
        var sample = ScalarSample.newInstance();
        var changes = (ChangesImpl) sample.vmf().changes();
        changes.start();
        sample.setDescription("recorded");
        changes.stop();
        var recorded = changes.all().get(0);
        var published = changes.transactions().get(0);

        changes.enableModelVersioning();
        try {
            sample.setDescription("version only");
            var before = changes.modelVersion();
            rejectRecording(() -> {
                try (var rejected = JournalRecorder.start(sample, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
                    throw new AssertionError("Manual versioning must retain ownership");
                }
            });
            check(changes.isModelVersioningEnabled() && changes.modelVersion() == before,
                    "Rejected start leaves manual versioning unchanged");
            sample.setDescription("still versioned");
            check(changes.modelVersion().versionNumber() == before.versionNumber() + 1,
                    "Manual versioning continues without recording");
            check(changes.all().size() == 1 && changes.all().get(0) == recorded
                    && changes.transactions().size() == 1 && changes.transactions().get(0) == published,
                    "Earlier native edits and transactions keep their identities");
        } finally {
            changes.disableModelVersioning();
        }
    }

    private static void verifyRecordingOwnership() {
        var nativeRecording = ScalarSample.newInstance();
        var changes = nativeRecording.vmf().changes();
        changes.start();
        nativeRecording.setDescription("native edit");
        rejectRecording(() -> {
            try (var rejected = JournalRecorder.start(nativeRecording, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
                throw new AssertionError("An active native recording must retain ownership");
            }
        });
        check(changes.isModelVersioningEnabled() && changes.all().size() == 1,
                "Rejected start leaves the previous native history intact");
        changes.stop();
        check(changes.transactions().size() == 1, "The native owner can still publish its original edit");

        var original = ScalarSample.newInstance();
        try (var owner = JournalRecorder.start(original, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            original.setDescription("portable edit");
            rejectRecording(() -> {
                try (var rejected = JournalRecorder.start(original, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
                    throw new AssertionError("An active journal recorder must retain ownership");
                }
            });
            check(original.vmf().changes().isModelVersioningEnabled() && original.vmf().changes().all().size() == 1,
                    "Rejected start leaves the first journal and its history intact");
            check(owner.finish().transactions().get(0).operations().get(0).after().get(0).text().equals("portable edit"),
                    "The original recorder can still finish");
        }

        var published = ScalarSample.newInstance();
        rejectRecording(() -> JournalRecorder.recordFrame(published, BINDING, CHECKPOINT, BASE_SEQUENCE, sample -> {
            sample.setDescription("published");
            sample.vmf().changes().publishTransaction();
            sample.setOptionalInt(88);
        }));
        var publishedHistory = published.vmf().changes();
        check("published".equals(published.getDescription()) && published.getOptionalInt() == 88
                        && !publishedHistory.isModelVersioningEnabled() && publishedHistory.all().size() == 2
                        && publishedHistory.transactions().size() == 1
                        && publishedHistory.transactions().get(0).changes().size() == 1,
                "One-frame recording rejects manual publication without rolling back edits or publishing the pending range");

        var split = ScalarSample.newInstance();
        rejectRecording(() -> JournalRecorder.recordFrame(split, BINDING, CHECKPOINT, BASE_SEQUENCE, sample -> {
            sample.setDescription("ungrouped");
            sample.vmf().changes().startTransaction();
            sample.setOptionalInt(88);
        }));
        check("ungrouped".equals(split.getDescription()) && split.getOptionalInt() == 88
                        && !split.vmf().changes().isModelVersioningEnabled(),
                "One-frame recording rejects a split result and leaves already applied edits in place");
    }

    private static void verifyExplicitPublication() {
        var manual = ScalarSample.newInstance();
        var manualHistory = manual.vmf().changes();
        try (var recorder = JournalRecorder.start(manual, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            manual.setDescription("native group");
            manualHistory.publishTransaction();
            manual.setOptionalInt(88);
            rejectRecording(recorder::publishFrame);
        }
        check("native group".equals(manual.getDescription()) && manual.getOptionalInt() == 88
                        && !manualHistory.isModelVersioningEnabled() && manualHistory.all().size() == 2
                        && manualHistory.transactions().size() == 1,
                "Explicit publication rejects an earlier native group and leaves pending edits unpublished");

        var converging = ScalarSample.newInstance();
        var convergingHistory = converging.vmf().changes();
        try (var recorder = JournalRecorder.start(converging, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            var listener = convergingHistory.transactions().addChangeListener(change -> {
                converging.setDescription("temporary");
                converging.setDescription("published");
            });
            try {
                converging.setDescription("published");
                rejectRecording(recorder::publishFrame);
            } finally {
                listener.unsubscribe();
            }
        }
        check("published".equals(converging.getDescription()) && !convergingHistory.isModelVersioningEnabled()
                        && convergingHistory.all().size() == 3 && convergingHistory.transactions().size() == 1,
                "Explicit publication rejects observer writes that return the graph to its original state");

        var caught = ScalarSample.newInstance();
        var caughtHistory = caught.vmf().changes();
        try (var recorder = JournalRecorder.start(caught, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            var listener = caughtHistory.transactions().addChangeListener(change -> {
                rejectRecording(recorder::publishFrame);
                rejectRecording(recorder::finish);
                rejectRecording(recorder::close);
            });
            try {
                caught.setDescription("published");
                check(recorder.publishFrame().orElseThrow().sequence() == BASE_SEQUENCE + 1,
                        "A caught nested lifecycle failure leaves the outer publication active");
            } finally {
                listener.unsubscribe();
            }
            check(caughtHistory.isModelVersioningEnabled(), "Caught nested failures leave the recorder active");
            caught.setOptionalInt(88);
            check(recorder.publishFrame().orElseThrow().sequence() == BASE_SEQUENCE + 2
                            && recorder.finish().transactions().size() == 2,
                    "The recorder continues from the next frame");
        }

        var uncaught = ScalarSample.newInstance();
        var uncaughtHistory = uncaught.vmf().changes();
        try (var recorder = JournalRecorder.start(uncaught, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            var listener = uncaughtHistory.transactions().addChangeListener(change -> recorder.publishFrame());
            try {
                uncaught.setDescription("published");
                rejectRecording(recorder::publishFrame);
            } finally {
                listener.unsubscribe();
            }
        }
        check("published".equals(uncaught.getDescription()) && !uncaughtHistory.isModelVersioningEnabled()
                        && uncaughtHistory.all().size() == 1 && uncaughtHistory.transactions().size() == 1,
                "Uncaught nested failure stops the recorder and retains the already published native transaction");
    }

    private static void verifyNativePublicationFailure() {
        var sample = ScalarSample.newInstance();
        var history = sample.vmf().changes();
        var invalidBinding = new JournalBinding() {
            @Override public String modelId() { return BINDING.modelId(); }
            @Override public int modelVersion() { return BINDING.modelVersion(); }
            @Override public String id(VObject value) { return BINDING.id(value); }
            @Override public String type(VObject value) { return BINDING.type(value); }
            @Override public VObject create(String type, String id) { return BINDING.create(type, id); }
            @Override public String propertyName(VObject value, String key) { return "unknown-property"; }
        };
        try (var recorder = JournalRecorder.start(sample, invalidBinding, CHECKPOINT, BASE_SEQUENCE)) {
            sample.setDescription("native publication");
            history.publishTransaction();
            check(history.isModelVersioningEnabled(), "Portable verification cannot interrupt native publication");
            rejectRecording(recorder::finish);
        }
        check("native publication".equals(sample.getDescription()) && !history.isModelVersioningEnabled()
                        && history.transactions().size() == 1
                        && history.transactions().get(0).changes().size() == 1,
                "The portable failure is reported later and leaves the published native group intact");
    }

    private static void rejectRecording(Runnable edit) {
        try {
            edit.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("Invalid journal recording must fail");
    }

    private static void write(Path checkpoint, Path journalFile) throws IOException {
        var working = ScalarSample.newBuilder().withDescription("draft").withOptionalLong(INITIAL_LONG)
                .withOptionalDouble(0.0).withOptionalInt(7).withSurrogateUnit('A').build();
        PLAIN.writeValue(checkpoint.toFile(), working);

        ChangeJournal journal;
        try (var recorder = JournalRecorder.start(working, BINDING, CHECKPOINT, BASE_SEQUENCE)) {
            working.setDescription(INSPECTION);
            working.setOptionalLong(Long.MIN_VALUE);
            working.setOptionalDouble(-0.0);
            working.setSurrogateUnit(SURROGATE);
            check(recorder.publishFrame().orElseThrow().sequence() == 41, "first portable frame sequence");
            check(recorder.publishFrame().isEmpty(), "empty publication must leave no portable frame");

            working.setOptionalDouble(FINAL_PROBE);
            working.setOptionalFloat(Float.MIN_VALUE);
            working.setDescription("ready");
            journal = recorder.finish();
        }
        checkFinal(working);
        Files.writeString(journalFile, VMFJournalCodec.write(journal));
    }

    private static ChangeJournal expected() {
        return new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE, List.of(
                new Frame(41, List.of(
                        set("NAME", ValueKind.STRING, "draft", ValueKind.STRING, INSPECTION),
                        set("optionalLong", ValueKind.LONG, "9007199254740993", ValueKind.LONG, "-9223372036854775808"),
                        set("probe", ValueKind.DOUBLE, "0x0.0p0", ValueKind.DOUBLE, "-0x0.0p0"),
                        set("surrogateUnit", ValueKind.CHAR, "65", ValueKind.CHAR, "55296"))),
                new Frame(42, List.of(
                        set("probe", ValueKind.DOUBLE, "-0x0.0p0", ValueKind.DOUBLE, "0x1.23p45"),
                        set("optionalFloat", ValueKind.NULL, null, ValueKind.FLOAT, "0x0.000002p-126"),
                        set("NAME", ValueKind.STRING, INSPECTION, ValueKind.STRING, "ready")))));
    }

    private static Operation set(String property, ValueKind from, String oldText, ValueKind to, String newText) {
        return new Operation(Kind.SET, ROOT, property, List.of(), List.of(new Value(from, oldText)),
                List.of(new Value(to, newText)), List.of());
    }

    private static void verify(Path checkpointFile, Path journalFile) throws IOException {
        var checkpoint = PLAIN.readValue(checkpointFile.toFile(), ScalarSample.class);
        checkCheckpoint(checkpoint);
        String wire = Files.readString(journalFile);
        var journal = VMFJournalCodec.read(wire);
        var envelope = (ObjectNode) PLAIN.readTree(wire);
        var expectedWire = PLAIN.readTree(VMFJournalCodec.write(expected()));
        check(journal.wireVersion() == 1 && expectedWire.equals(envelope),
                "exact V1 frame boundaries, property keys, and tagged values\nExpected: " + expectedWire + "\nActual: " + envelope);

        var firstFrame = new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(),
                journal.checkpoint(), journal.baseSequence(), List.of(journal.transactions().get(0)));
        var intermediate = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, firstFrame);
        check(intermediate != checkpoint && INSPECTION.equals(intermediate.getDescription())
                && intermediate.getOptionalLong() == Long.MIN_VALUE && intermediate.getSurrogateUnit() == SURROGATE
                && Double.doubleToRawLongBits(intermediate.getOptionalDouble()) == Long.MIN_VALUE
                && intermediate.getOptionalFloat() == null && intermediate.getOptionalInt() == 7,
                "frame 41 preserves the negative zero, width, and isolated UTF-16 unit");

        var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        check(replayed != checkpoint && replayed != intermediate, "each replay produces an independent model");
        checkFinal(replayed);
        verifyRejections(checkpoint, journal, envelope);
        checkCheckpoint(checkpoint);
    }

    private static void verifyControlXml(Path checkpointFile, Path controlFile) throws IOException {
        var checkpoint = PLAIN.readValue(checkpointFile.toFile(), ScalarSample.class);
        String wire = Files.readString(controlFile);
        String base64 = Base64.getEncoder().encodeToString(XML_INSPECTION.getBytes(StandardCharsets.UTF_8));
        check(wire.contains("encoding=\"base64\"") && wire.contains(base64), "XML encodes control characters and carriage returns as UTF-8 Base64");

        var journal = VMFJournalCodec.read(wire, VMFDocumentFormat.XML);
        var expected = new ChangeJournal(MODEL, 1, ROOT, CHECKPOINT, BASE_SEQUENCE, List.of(new Frame(41, List.of(
                set("NAME", ValueKind.STRING, "draft", ValueKind.STRING, XML_INSPECTION)))));
        check(journal.wireVersion() == 1 && PLAIN.readTree(VMFJournalCodec.write(expected)).equals(
                PLAIN.readTree(VMFJournalCodec.write(journal))), "decoded XML preserves the exact V1 logical journal");
        var replayed = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        check(replayed != checkpoint && XML_INSPECTION.equals(replayed.getDescription())
                && replayed.getOptionalLong() == INITIAL_LONG && replayed.getOptionalInt() == 7,
                "XML replay preserves the exact text without altering untouched fields");
        checkCheckpoint(checkpoint);
    }

    private static void verifyRejections(ScalarSample checkpoint, ChangeJournal journal, ObjectNode envelope) throws IOException {
        rejectReplay("checkpoint does not match", () -> JournalReplayer.replay(checkpoint, BINDING, "different", journal));
        var stale = checkpoint.clone();
        stale.setOptionalDouble(-0.0);
        rejectReplay("Old value differs for probe", () -> JournalReplayer.replay(stale, BINDING, CHECKPOINT, journal));
        check("draft".equals(stale.getDescription()) && stale.getOptionalLong() == INITIAL_LONG
                && Double.doubleToRawLongBits(stale.getOptionalDouble()) == Long.MIN_VALUE,
                "failed replay preserves even a mismatching input checkpoint");

        var wrongRoot = VMFJournalCodec.read(envelope.deepCopy().put("root", "unknown").toString());
        rejectReplay("root does not match", () -> JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, wrongRoot));

        var unknownTarget = envelope.deepCopy();
        lastOperation(unknownTarget).put("target", "unknown");
        var lateFailure = VMFJournalCodec.read(unknownTarget.toString());
        rejectReplay("Unknown or changed object ID unknown", () -> JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, lateFailure));

        var unmappedProperty = envelope.deepCopy();
        lastOperation(unmappedProperty).put("property", "description");
        var invalidKey = VMFJournalCodec.read(unmappedProperty.toString());
        rejectReplay("Invalid journal property description", () -> JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, invalidKey));

        rejectWire("unsupported journal version", envelope.deepCopy().put("version", 99));
        var skippedFrame = envelope.deepCopy();
        ((ObjectNode) skippedFrame.path("transactions").path(1)).put("sequence", 43);
        rejectWire("sequence", skippedFrame);
        var invalidValue = envelope.deepCopy();
        ((ObjectNode) lastOperation(invalidValue).path("after").path(0)).putNull("text");
        rejectWire("text", invalidValue);
    }

    private static ObjectNode lastOperation(ObjectNode envelope) {
        return (ObjectNode) envelope.path("transactions").path(1).path("operations").path(2);
    }

    private static void checkCheckpoint(ScalarSample state) {
        check("draft".equals(state.getDescription()) && state.getOptionalLong() == INITIAL_LONG
                && Double.doubleToRawLongBits(state.getOptionalDouble()) == 0L && state.getSurrogateUnit() == 'A'
                && state.getOptionalFloat() == null && state.getOptionalInt() == 7, "untouched base checkpoint");
    }

    private static void checkFinal(ScalarSample state) {
        check("ready".equals(state.getDescription()) && state.getOptionalLong() == Long.MIN_VALUE
                && state.getOptionalDouble() == FINAL_PROBE && state.getSurrogateUnit() == SURROGATE
                && state.getOptionalFloat() == Float.MIN_VALUE && state.getOptionalInt() == 7, "final replay state");
    }

    private static void rejectReplay(String message, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException failure) {
            if (failure.getMessage().contains(message)) return;
            throw failure;
        }
        throw new AssertionError("Expected journal replay failure containing " + message);
    }

    private static void rejectWire(String message, JsonNode document) throws IOException {
        try {
            VMFJournalCodec.read(document.toString());
        } catch (IOException failure) {
            if (failure.getMessage().contains(message)) return;
            throw failure;
        }
        throw new AssertionError("Expected journal format failure containing " + message);
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
