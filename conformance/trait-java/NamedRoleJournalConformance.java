package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.traits.NamedOwnedNote;
import eu.mihosoft.vmf.conformance.traits.NamedOwnedRole;
import eu.mihosoft.vmf.conformance.traits.NamedOwnedTask;
import eu.mihosoft.vmf.conformance.traits.NamedRoleFolder;
import eu.mihosoft.vmf.conformance.traits.NamedRoleJournalHistory;
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

final class NamedRoleJournalConformance {
    private static final ObjectMapper SNAPSHOT = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "vmf-test.named-role-ownership"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) {
            if (value instanceof NamedRoleJournalHistory) return "root";
            if (value instanceof NamedRoleFolder) return ((NamedRoleFolder) value).getName();
            if (value instanceof NamedOwnedRole) return ((NamedOwnedRole) value).getLabel();
            throw new AssertionError(value);
        }
        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError(id); }
    };

    private NamedRoleJournalConformance() { }

    static void exchange(Path directory, String author, boolean writing) throws IOException {
        var checkpoint = directory.resolve(author + "-named-role-journal-checkpoint.json");
        if (writing) SNAPSHOT.writeValue(checkpoint.toFile(), initial());
        var journal = writing ? record() : null;
        for (var format : VMFDocumentFormat.values()) {
            var path = directory.resolve(author + "-named-role-journal." + format.name().toLowerCase(Locale.ROOT));
            if (writing) Files.writeString(path, VMFJournalCodec.write(journal, format));
            var original = SNAPSHOT.readValue(checkpoint.toFile(), NamedRoleJournalHistory.class);
            verify(original, VMFJournalCodec.read(Files.readString(path), format));
        }
    }

    private static NamedRoleJournalHistory initial() {
        var root = NamedRoleJournalHistory.newInstance();
        var moving = NamedOwnedTask.newBuilder().withLabel("moving").withTaskNumber(71).build();
        var left = NamedRoleFolder.newBuilder().withName("left").build();
        left.setSingle(moving);
        left.getOrdered().add(NamedOwnedNote.newBuilder().withLabel("stay-ordered").build());
        var right = NamedRoleFolder.newBuilder().withName("right").build();
        right.getOrdinary().add(NamedOwnedNote.newBuilder().withLabel("stay-list").build());
        right.getUnordered().add(NamedOwnedNote.newBuilder().withLabel("stay-set").build());
        root.setLeft(left);
        root.setRight(right);
        root.setSelected(moving);
        return root;
    }

    private static ChangeJournal record() {
        var root = initial();
        var moving = (NamedOwnedTask) root.getSelected();
        try (var recorder = JournalRecorder.start(root, BINDING, "initial", 10)) {
            moving.setOrdinaryOwner(root.getRight());
            moving.setOrderedOwner(root.getLeft());
            moving.setUnorderedOwner(root.getRight());
            check(recorder.publishFrame().orElseThrow().sequence() == 11, "First frame");
            root.setSelected(null);
            moving.setUnorderedOwner(null);
            check(recorder.publishFrame().orElseThrow().sequence() == 12, "Detached frame");
            moving.setSingleOwner(root.getRight());
            moving.setTaskNumber(72);
            root.setSelected(moving);
            return recorder.finish();
        }
    }

    private static void verify(NamedRoleJournalHistory original, ChangeJournal journal) {
        var frames = journal.transactions();
        check(journal.wireVersion() == 1 && frames.stream().map(ChangeJournal.Frame::sequence)
                        .collect(Collectors.toList()).equals(List.of(11L, 12L, 13L)),
                "Three consecutive V1 frames");
        check(operations(frames.get(0)).equals(List.of("SET left.single",
                        "LIST_ADD right.ordinary", "LIST_REMOVE right.ordinary",
                        "LIST_ADD left.ordered", "LIST_REMOVE left.ordered", "REF_SET_EDIT right.unordered"))
                        && operations(frames.get(1)).equals(List.of("SET root.selected", "REF_SET_EDIT right.unordered"))
                        && operations(frames.get(2)).equals(List.of("SET right.single", "SET moving.taskNumber", "SET root.selected")),
                "Forward journal operations have their native owning order and omit synthetic inverse changes");
        check(frames.stream().flatMap(frame -> frame.operations().stream()).allMatch(edit -> edit.created().isEmpty())
                        && frames.get(0).operations().subList(1, 5).stream()
                        .allMatch(edit -> edit.indices().equals(List.of(1))),
                "The same identity moves through both indexed owning slot shapes without creation");

        var first = replayPrefix(original, journal, 1);
        var unordered = (NamedOwnedTask) first.getSelected();
        check(first.getRight().getUnordered().contains(unordered) && unordered.getUnorderedOwner() == first.getRight()
                        && unordered.getSingleOwner() == null && unordered.getOrdinaryOwner() == null
                        && unordered.getOrderedOwner() == null,
                "First frame restores the unordered inverse and clears all former inverses");
        var detached = replayPrefix(original, journal, 2);
        check(detached.getSelected() == null && detached.getLeft().getSingle() == null
                        && detached.getRight().getSingle() == null && detached.getRight().getUnordered().size() == 1
                        && detached.getRight().getUnordered().iterator().next().getLabel().equals("stay-set"),
                "Second frame leaves the moved child detached without a dangling reference");

        var result = JournalReplayer.replay(original, BINDING, "initial", journal);
        var moving = (NamedOwnedTask) result.getRight().getSingle();
        check(moving != null && moving.getSingleOwner() == result.getRight()
                        && moving.getTaskNumber() == 72 && result.getSelected() == moving
                        && result.getRight().asReadOnly().getSingle().getSingleOwner() == result.getRight().asReadOnly(),
                "Restored moved child stays in its concrete type and retains reference and inverse identity");
        check(result.getLeft().getSingle() == null
                        && result.getLeft().getOrdered().size() == 1 && result.getRight().getOrdinary().size() == 1
                        && result.getRight().getUnordered().size() == 1,
                "The resident members survived every transplant");
        check(original.getLeft().getSingle() == original.getSelected() && original.getRight().getSingle() == null
                        && result.getSelected() != original.getSelected(),
                "Replay leaves the loaded checkpoint available and produces an independent graph");
    }

    private static List<String> operations(ChangeJournal.Frame frame) {
        return frame.operations().stream().map(edit -> edit.kind() + " " + edit.target() + "." + edit.property())
                .collect(Collectors.toList());
    }

    private static NamedRoleJournalHistory replayPrefix(NamedRoleJournalHistory original, ChangeJournal journal, int count) {
        var prefix = new ChangeJournal(journal.model(), journal.modelVersion(), journal.root(),
                journal.checkpoint(), journal.baseSequence(), journal.transactions().subList(0, count));
        return JournalReplayer.replay(original, BINDING, "initial", prefix);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
