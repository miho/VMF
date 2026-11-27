package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.traits.KeyedUnorderedHistory;
import eu.mihosoft.vmf.conformance.traits.NamedKeyedUnorderedFolder;
import eu.mihosoft.vmf.conformance.traits.RoleKey;
import eu.mihosoft.vmf.conformance.traits.RoleSetNote;
import eu.mihosoft.vmf.conformance.traits.RoleSetTask;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

final class KeyedUnorderedJournalConformance {
    private static final ObjectMapper SNAPSHOT = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "vmf-test.keyed-unordered"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) {
            if (value instanceof KeyedUnorderedHistory) return "root";
            if (value instanceof NamedKeyedUnorderedFolder) return ((NamedKeyedUnorderedFolder)value).getName();
            if (value instanceof RoleKey) return "key-" + ((RoleKey)value).getCode();
            if (value instanceof RoleSetTask) return "task-" + ((RoleSetTask)value).getTaskId();
            if (value instanceof RoleSetNote) return "note-" + ((RoleSetNote)value).getMessage();
            throw new AssertionError(value);
        }
        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            if (RoleSetNote.class.getName().equals(type) && id.startsWith("note-")) {
                return RoleSetNote.newBuilder().withMessage(id.substring("note-".length())).build();
            }
            throw new AssertionError(type);
        }
    };

    private KeyedUnorderedJournalConformance() { }

    static void exchange(Path directory, String author, boolean writing) throws IOException {
        Path checkpoint = directory.resolve(author + "-keyed-unordered-journal-checkpoint.json");
        if (writing) SNAPSHOT.writeValue(checkpoint.toFile(), initial());
        for (boolean fromCallback : new boolean[]{false, true}) {
            ChangeJournal journal = writing ? record(fromCallback) : null;
            String suffix = fromCallback ? "-reentrant" : "";
            for (var format : VMFDocumentFormat.values()) {
                Path path = directory.resolve(author + "-keyed-unordered-journal" + suffix + "."
                        + format.name().toLowerCase(Locale.ROOT));
                if (writing) Files.writeString(path, VMFJournalCodec.write(journal, format));
                var base = SNAPSHOT.readValue(checkpoint.toFile(), KeyedUnorderedHistory.class);
                verify(base, VMFJournalCodec.read(Files.readString(path), format), fromCallback);
            }
        }
    }

    private static KeyedUnorderedHistory initial() {
        var root = KeyedUnorderedHistory.newInstance();
        var first = RoleKey.newBuilder().withCode("first").build();
        var second = RoleKey.newBuilder().withCode("second").build();
        var next = RoleKey.newBuilder().withCode("next").build();
        var task = RoleSetTask.newBuilder().withTaskId("moving").withToken(first).build();
        var note = RoleSetNote.newBuilder().withMessage("right").withToken(second).build();
        root.setLeft(NamedKeyedUnorderedFolder.newBuilder().withName("left").withItems(task).build());
        root.setRight(NamedKeyedUnorderedFolder.newBuilder().withName("right").withItems(note).build());
        root.getKeys().addAll(List.of(first, second, next));
        root.setSelected(task);
        return root;
    }

    private static ChangeJournal record(boolean fromCallback) {
        var root = initial();
        var task = (RoleSetTask)root.getSelected();
        var first = root.getKeys().get(0);
        var next = root.getKeys().get(2);
        var observer = fromCallback ? root.getLeft().vmf().changes().addPropertyListener("items", edit -> {
            if (edit.referenceSetChange().orElseThrow().removed().stream().noneMatch(value -> value == task)) return;
            check(root.getLeft().findItemsByToken(first).isEmpty()
                    && root.getRight().findItemsByToken(first).orElseThrow() == task, "Lookup follows the pending transfer");
            try {
                task.setToken(root.getKeys().get(1));
                throw new AssertionError("A pending rekey cannot take another child's key");
            } catch (IllegalArgumentException expected) {
                check(task.getToken() == first, "Rejected rekey preserves the current key");
            }
            task.setToken(next);
            check(root.getRight().findItemsByToken(next).orElseThrow() == task, "Pending lookup follows the rekey");
        }) : null;
        try (var recorder = JournalRecorder.start(root, BINDING, "initial", 0)) {
            check(root.getRight().getItems().add(task), "Transfer between identity-keyed unordered sets");
            if (!fromCallback) task.setToken(next);
            check(task.getToken() == next, "The rekey is visible after transfer");
            var newNote = RoleSetNote.newBuilder().withMessage("new").withToken(root.getKeys().get(0)).build();
            check(root.getLeft().getItems().add(newNote), "Create a keyed child against an existing reference key");
            root.setSelected(newNote);
            return recorder.finish();
        } finally {
            if (observer != null) observer.unsubscribe();
        }
    }

    private static void verify(KeyedUnorderedHistory checkpoint, ChangeJournal journal, boolean fromCallback) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 transaction");
        var edits = journal.transactions().get(0).operations();
        var membership = ChangeJournal.Kind.REF_SET_EDIT;
        var property = ChangeJournal.Kind.SET;
        check(edits.stream().map(ChangeJournal.Operation::kind).collect(Collectors.toList()).equals(fromCallback
                ? List.of(membership, property, membership, membership, property)
                : List.of(membership, membership, property, membership, property)),
                "V1 uses unordered membership and property edits");
        check(edits.stream().map(ChangeJournal.Operation::property).collect(Collectors.toList()).equals(fromCallback
                ? List.of("items", "token", "items", "items", "selected")
                : List.of("items", "items", "token", "items", "selected")),
                "A reentrant rekey replays before the destination addition");
        check(edits.get(3).created().size() == 1 && edits.get(3).created().get(0).id().equals("note-new"), "Fresh child definition");
        var restored = JournalReplayer.replay(checkpoint, BINDING, "initial", journal);
        var task = restored.getRight().findItemsByToken(restored.getKeys().get(2)).orElseThrow();
        var created = restored.getLeft().findItemsByToken(restored.getKeys().get(0)).orElseThrow();
        check(((RoleSetTask)task).getTaskId().equals("moving") && ((RoleSetNote)created).getMessage().equals("new")
                && restored.getSelected() == created && restored.getRight().getItems().size() == 2
                && restored.getRight().findItemsByToken(restored.getKeys().get(0)).isEmpty()
                && restored.getLeft().getItems().size() == 1, "Replayed links, old-key removal and selected alias");
        check(restored.getRight().asReadOnly().findItemsByToken(restored.getKeys().get(2).asReadOnly()).orElseThrow()
                == task.asReadOnly(), "Projected keyed lookup shares the live alias");
        check(checkpoint.getLeft().findItemsByToken(checkpoint.getKeys().get(0)).orElseThrow() == checkpoint.getSelected()
                && checkpoint.getRight().getItems().size() == 1 && checkpoint.getRight().findItemsByToken(checkpoint.getKeys().get(2)).isEmpty(),
                "Replay leaves the original checkpoint available");
    }

    private static void check(boolean test, String message) {
        if (!test) throw new AssertionError(message);
    }
}
