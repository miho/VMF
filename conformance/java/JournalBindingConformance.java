package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.History;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

final class JournalBindingConformance {
    private static final String IDENTITY_FIXTURE = "application-checkpoint-identity";
    private static final String CHECKPOINT = "application-start";
    private static final String MODEL = "conformance.application-keys";
    private static final ObjectMapper SNAPSHOTS = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }

        @Override public String id(VObject object) {
            if (object instanceof ArchiveRoot || object instanceof ValueSelection) return "root";
            if (object instanceof ArchiveNode) return ((ArchiveNode) object).getName();
            if (object instanceof ValueLabel) return ((ValueLabel) object).getText();
            throw new IllegalArgumentException("Unknown model object");
        }

        @Override public String type(VObject object) {
            if (object instanceof ArchiveRoot) return "archive/v1";
            if (object instanceof ArchiveNode) return "entry/v1";
            if (object instanceof ValueSelection) return "selection/v1";
            if (object instanceof ValueLabel) return "label/v1";
            throw new IllegalArgumentException("Unknown application type");
        }

        @Override public VObject create(String type, String id) {
            if (type.equals("entry/v1")) return node(id);
            if (type.equals("label/v1")) return label("prototype");
            throw new IllegalArgumentException("Unknown creation type " + type);
        }

        @Override public String propertyKey(VObject object, String property) {
            if (object instanceof ArchiveNode && property.equals("children")) return "contents";
            if (object instanceof ArchiveNode && property.equals("name")) return "code";
            if (object instanceof ValueLabel && property.equals("text")) return "caption";
            if (object instanceof ValueSelection && property.equals("current")) return "selected";
            return property;
        }

        @Override public String propertyName(VObject object, String key) {
            if (object instanceof ArchiveNode && (key.equals("contents") || key.equals("old-contents"))) return "children";
            if (object instanceof ArchiveNode && key.equals("code")) return "name";
            if (object instanceof ValueLabel && key.equals("caption")) return "text";
            if (object instanceof ValueSelection && key.equals("selected")) return "current";
            return key;
        }
    };

    private JournalBindingConformance() { }

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args[1]);
        boolean write = args[0].equals("write");
        if (write) {
            Files.createDirectories(directory);
            var archive = ArchiveRoot.newBuilder().withHead(node("main")).build();
            var baseline = label("baseline");
            var selection = ValueSelection.newBuilder().withCurrent(baseline).withSecond(baseline).build();
            write(directory, "application-mutable", archive, () -> {
                var child = node("new-item");
                archive.getHead().getChildren().add(child);
                archive.getHead().setAnchor(child);
            });
            write(directory, "application-immutable", selection, () -> {
                var value = label("created");
                selection.setCurrent(value);
                selection.setSecond(value);
            });
            writeCheckpointIdentities(directory);
        }

        var producer = write ? "java-" : "csharp-";
        for (var format : VMFDocumentFormat.values()) {
            var archive = checkpoint(directory, producer, "application-mutable", ArchiveRoot.class);
            var mutable = journal(directory, producer, "application-mutable", format);
            var operation = mutable.transactions().get(0).operations().get(0);
            check(mutable.wireVersion() == 1 && operation.property().equals("contents")
                            && operation.created().get(0).type().equals("entry/v1")
                            && operation.created().get(0).properties().containsKey("code"),
                    "Application keys and type appear on existing V1 records");
            var restored = JournalReplayer.replay(archive, BINDING, CHECKPOINT, mutable);
            var child = restored.getHead().getChildren().get(0);
            check(child.getName().equals("new-item") && child.getParent() == restored.getHead()
                            && restored.getHead().getAnchor() == child && archive.getHead().getChildren().isEmpty(),
                    "Creation preserved containment and alias on a fresh graph");

            var selection = checkpoint(directory, producer, "application-immutable", ValueSelection.class);
            var immutable = journal(directory, producer, "application-immutable", format);
            operation = immutable.transactions().get(0).operations().get(0);
            check(operation.property().equals("selected") && operation.created().get(0).type().equals("label/v1")
                            && operation.created().get(0).properties().containsKey("caption"),
                    "Immutable creation uses its independent application type and property IDs");
            var copied = JournalReplayer.replay(selection, BINDING, CHECKPOINT, immutable);
            check(copied.getCurrent() == copied.getSecond() && copied.getCurrent().getText().equals("created")
                            && selection.getCurrent().getText().equals("baseline"),
                    "Immutable aliases survive application-bound creation");

            verifyCheckpointIdentities(checkpoint(directory, producer, IDENTITY_FIXTURE, ValueSelection.class),
                    journal(directory, producer, IDENTITY_FIXTURE, format));
            if (format == VMFDocumentFormat.JSON) verifyStaleAlias(archive, mutable);
        }
    }

    private static void writeCheckpointIdentities(Path directory) throws IOException {
        var left = label("same");
        var right = label("same");
        var selection = ValueSelection.newBuilder().withCurrent(left).withSecond(left).withSelected(right).build();
        SNAPSHOTS.writeValue(directory.resolve("java-" + IDENTITY_FIXTURE + "-checkpoint.json").toFile(), selection);
        try (var recorder = JournalRecorder.start(selection, checkpointIdentityBinding(selection), CHECKPOINT, 20)) {
            selection.setCurrent(right);
            recorder.publishFrame();
            selection.setSecond(right);
            var recorded = recorder.finish();
            for (var format : VMFDocumentFormat.values()) {
                Files.writeString(file(directory, "java-", IDENTITY_FIXTURE, format), VMFJournalCodec.write(recorded, format));
            }
        }
    }

    private static JournalBinding checkpointIdentityBinding(ValueSelection selection) {
        var left = selection.getCurrent();
        var right = selection.getSelected().iterator().next();
        check(left == selection.getSecond() && left != right && left.equals(right),
                "The two immutable checkpoint values are content equal but have distinct identities");
        var table = new IdentityHashMap<VObject, String>();
        table.put(left, "stable-left");
        table.put(right, "stable-right");
        return new JournalBinding() {
            @Override public String modelId() { return "conformance.checkpoint-identity"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject object) {
                if (object instanceof ValueSelection) return "selection";
                String id = table.get(object);
                if (id == null) throw new IllegalArgumentException("Unknown immutable checkpoint identity");
                return id;
            }
            @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { throw new IllegalArgumentException("No object creation expected"); }
        };
    }

    @SuppressWarnings("deprecation")
    private static void verifyCheckpointIdentities(ValueSelection selection, ChangeJournal journal) {
        var binding = checkpointIdentityBinding(selection);
        var left = selection.getCurrent();
        var right = selection.getSelected().iterator().next();
        var beforeId = new ChangeJournal.Value(ChangeJournal.ValueKind.IMMUTABLE_REFERENCE, "stable-left");
        var afterId = new ChangeJournal.Value(ChangeJournal.ValueKind.IMMUTABLE_REFERENCE, "stable-right");
        check(journal.wireVersion() == 1 && journal.transactions().size() == 2, "Expected two V1 frames");
        for (int index = 0; index < 2; index++) {
            var frame = journal.transactions().get(index);
            var edit = frame.operations().get(0);
            check(frame.operations().size() == 1 && edit.kind() == ChangeJournal.Kind.SET
                            && edit.property().equals(index == 0 ? "current" : "second")
                            && edit.before().equals(List.of(beforeId)) && edit.after().equals(List.of(afterId))
                            && edit.created().isEmpty(),
                    "A reference edit uses the application's immutable IDs");
        }

        var history = new History<ValueSelection>(binding);
        history.addSegment(CHECKPOINT, selection, journal);
        var before = history.seek(20);
        var midway = history.seek(21);
        var after = history.seek(22);
        check(before != selection && midway != before && after != midway
                        && before.getCurrent() == left && before.getSecond() == left
                        && midway.getCurrent() == right && midway.getSecond() == left
                        && after.getCurrent() == right && after.getSecond() == right
                        && after.getSelected().iterator().next() == right,
                "Seeks share immutable identities and return independent mutable roots");
        var replayed = JournalReplayer.replay(selection, binding, CHECKPOINT, journal);
        midway.setCurrent(left);
        check(replayed.getCurrent() == right && replayed.getSecond() == right && history.seek(21).getCurrent() == right
                        && selection.getCurrent() == left && selection.getSecond() == left,
                "Replay and edits to one seek leave the checkpoint and later seeks unchanged");
    }

    private static void write(Path directory, String name, VObject target, Runnable edit) throws IOException {
        SNAPSHOTS.writeValue(directory.resolve("java-" + name + "-checkpoint.json").toFile(), target);
        try (var recorder = JournalRecorder.start(target, BINDING, CHECKPOINT, 0)) {
            edit.run();
            var journal = recorder.finish();
            for (var format : VMFDocumentFormat.values())
                Files.writeString(file(directory, "java-", name, format), VMFJournalCodec.write(journal, format));
        }
    }

    private static Path file(Path directory, String producer, String fixture, VMFDocumentFormat format) {
        return directory.resolve(producer + fixture + "-journal." + format.name().toLowerCase(Locale.ROOT));
    }

    private static ChangeJournal journal(Path directory, String producer, String fixture, VMFDocumentFormat format) throws IOException {
        return VMFJournalCodec.read(Files.readString(file(directory, producer, fixture, format)), format);
    }

    private static <T> T checkpoint(Path directory, String producer, String fixture, Class<T> type) throws IOException {
        return SNAPSHOTS.readValue(directory.resolve(producer + fixture + "-checkpoint.json").toFile(), type);
    }

    private static void verifyStaleAlias(ArchiveRoot archive, ChangeJournal journal) throws IOException {
        var wire = SNAPSHOTS.readTree(VMFJournalCodec.write(journal));
        var operation = (ObjectNode) wire.path("transactions").path(0).path("operations").path(0);
        operation.put("property", "old-contents");
        var invalid = VMFJournalCodec.read(wire.toString());
        try {
            JournalReplayer.replay(archive, BINDING, CHECKPOINT, invalid);
            throw new AssertionError("An inbound property key which does not round-trip was accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("Invalid journal property") && archive.getHead().getChildren().isEmpty(),
                    "Stale alias rejection leaves the checkpoint untouched");
        }
    }

    private static ArchiveNode node(String id) { return ArchiveNode.newBuilder().withName(id).build(); }
    private static ValueLabel label(String text) { return ValueLabel.newBuilder().withText(text).build(); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
