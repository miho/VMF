package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.ordinarycontainment.ReadingRoom;
import eu.mihosoft.vmf.conformance.ordinarycontainment.Shelf;
import eu.mihosoft.vmf.conformance.ordinarycontainment.Volume;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VIterator.IterationStrategy;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class UnorderedCursorConformance {
    private static final JournalBinding REFERENCES = binding("unordered-cursor-reference");
    private static final JournalBinding OWNERSHIP = binding("unordered-cursor-owning");

    private UnorderedCursorConformance() {}

    static void verify() throws IOException {
        check(VMFJournalCodec.write(referenceInsertion(true)).equals(VMFJournalCodec.write(referenceInsertion(false))),
                "Reference cursor insertion and direct add use the same V1 membership envelope");
        verifyReferenceReplacement();
        check(VMFJournalCodec.write(owningInsertion(true)).equals(VMFJournalCodec.write(owningInsertion(false))),
                "Ownership transfer from a cursor and direct add use the same V1 membership envelopes");
        verifyOwningReplacement();
    }

    private static ChangeJournal referenceInsertion(boolean cursorEdit) {
        var root = referenceCheckpoint();
        var view = root.asReadOnly();
        var first = root.getChildren().get(0);
        var second = root.getChildren().get(1);
        var third = root.getChildren().get(2);
        try (var recorder = JournalRecorder.start(root, REFERENCES, "base", 0)) {
            var observed = new ArrayList<Change>();
            var watch = root.vmf().changes().addLocalListener(observed::add);
            try {
                if (cursorEdit) {
                    var cursor = root.vmf().content().iterator(IterationStrategy.UNIQUE_PROPERTY);
                    move(cursor, root, first, second, third, first);
                    check(cursor.isAddSupported(), "The selected unordered reference slot supports insertion");
                    cursor.add(third);
                    move(cursor, second);
                    check(!cursor.hasNext(), "Traversal skips the inserted reference");
                } else {
                    root.getPeers().add(third);
                }
                check(view.getPeers().contains(third.asReadOnly()), "The projected set is live");
                check(root.getPeers().stream().map(ArchiveNode::getName).collect(Collectors.toList())
                        .equals(cursorEdit ? List.of("a", "c", "b") : List.of("a", "b", "c")),
                        "Live Java order tracks placement, even though this is an unordered relationship");
                check(observed.size() == 1, "One membership event for adding a reference");
                expectEvent(observed.get(0), "peers", List.of(), List.of(third));
                var journal = recorder.finish();
                expectOperation(only(journal), "root", "peers", List.of(), List.of("c"));
                var replayed = JournalReplayer.replay(referenceCheckpoint(), REFERENCES, "base", journal);
                check(replayed.getPeers().contains(replayed.getChildren().get(2)), "The V1 journal restores the reference alias");
                return journal;
            } finally {
                watch.unsubscribe();
            }
        }
    }

    private static void verifyReferenceReplacement() {
        var root = referenceCheckpoint();
        var first = root.getChildren().get(0);
        var second = root.getChildren().get(1);
        var replacement = root.getChildren().get(2);
        var cursor = root.vmf().content().iterator(IterationStrategy.UNIQUE_PROPERTY);
        move(cursor, root, first, second, replacement, first);
        try (var recorder = JournalRecorder.start(root, REFERENCES, "base", 0)) {
            var observed = new ArrayList<Change>();
            var watch = root.vmf().changes().addLocalListener(observed::add);
            try {
                cursor.set(replacement);
                move(cursor, second);
                check(root.getPeers().stream().collect(Collectors.toList()).equals(List.of(replacement, second)),
                        "Replacement keeps the selected position");
                check(observed.size() == 1, "Replacement publishes one membership event");
                expectEvent(observed.get(0), "peers", List.of(first), List.of(replacement));
                expectOperation(only(recorder.finish()), "root", "peers", List.of("a"), List.of("c"));
            } finally {
                watch.unsubscribe();
            }
            observed.get(0).undo();
            check(root.getPeers().contains(first) && !root.getPeers().contains(replacement), "History undoes membership");
        }
    }

    private static ChangeJournal owningInsertion(boolean cursorEdit) {
        var root = owningCheckpoint();
        var target = root.getShelves().get(0);
        var source = root.getShelves().get(1);
        var first = target.getVolumes().iterator().next();
        var last = target.getVolumes().stream().skip(1).findFirst().orElseThrow();
        var moving = source.getVolumes().iterator().next();
        try (var recorder = JournalRecorder.start(root, OWNERSHIP, "base", 0)) {
            var trace = new ArrayList<String>();
            var removed = source.vmf().changes().addLocalListener(change -> {
                check(moving.getShelf() == source, "The original inverse is visible during source removal");
                expectEvent(change, "volumes", List.of(moving), List.of());
                trace.add("source");
            });
            var inverse = moving.vmf().changes().addLocalListener(change ->
                    trace.add(change.propertyChange().orElseThrow().newValue() == null ? "detach" : "attach"));
            var added = target.vmf().changes().addLocalListener(change -> {
                check(moving.getShelf() == target, "The new inverse is visible during destination addition");
                expectEvent(change, "volumes", List.of(), List.of(moving));
                trace.add("target");
            });
            try {
                if (cursorEdit) {
                    var cursor = target.vmf().content().iterator(IterationStrategy.CONTAINMENT_TREE);
                    move(cursor, target, first);
                    cursor.add(moving);
                    move(cursor, last);
                    check(!cursor.hasNext(), "Traversal skips the transferred child");
                } else {
                    target.getVolumes().add(moving);
                }
                check(source.getVolumes().isEmpty() && moving.getShelf() == target, "The inverse agrees with both sets");
                check(trace.equals(List.of("source", "detach", "attach", "target")), "Ownership transfer has native event order");
                check(target.getVolumes().stream().map(Volume::getName).collect(Collectors.toList())
                        .equals(cursorEdit ? List.of("a", "c", "b") : List.of("a", "b", "c")),
                        "The cursor inserts the owned member at its selected position");
                var journal = recorder.finish();
                var edits = journal.transactions().get(0).operations();
                check(journal.wireVersion() == 1 && journal.transactions().size() == 1 && edits.size() == 2,
                        "Transfer records source and target membership once each");
                expectOperation(edits.get(0), "source", "volumes", List.of("c"), List.of());
                expectOperation(edits.get(1), "target", "volumes", List.of(), List.of("c"));
                var replayed = JournalReplayer.replay(owningCheckpoint(), OWNERSHIP, "base", journal);
                var owner = replayed.getShelves().get(0);
                check(replayed.getShelves().get(1).getVolumes().isEmpty()
                                && owner.getVolumes().stream().filter(volume -> volume.getName().equals("c"))
                                    .findFirst().orElseThrow().getShelf() == owner,
                        "The V1 journal restores the inverse and transferred membership");
                return journal;
            } finally {
                removed.unsubscribe();
                inverse.unsubscribe();
                added.unsubscribe();
            }
        }
    }

    private static ArchiveNode referenceCheckpoint() {
        var root = node("root");
        var first = node("a");
        var second = node("b");
        root.getChildren().addAll(List.of(first, second, node("c")));
        root.getPeers().addAll(List.of(first, second));
        return root;
    }

    private static void verifyOwningReplacement() {
        var root = owningCheckpoint();
        var target = root.getShelves().get(0);
        var source = root.getShelves().get(1);
        var first = target.getVolumes().iterator().next();
        var last = target.getVolumes().stream().skip(1).findFirst().orElseThrow();
        var moving = source.getVolumes().iterator().next();
        try (var recorder = JournalRecorder.start(root, OWNERSHIP, "base", 0)) {
            var cursor = target.vmf().content().iterator(IterationStrategy.CONTAINMENT_TREE);
            move(cursor, target, first);
            cursor.set(moving);
            move(cursor, last);
            check(target.getVolumes().stream().collect(Collectors.toList()).equals(List.of(moving, last))
                    && source.getVolumes().isEmpty() && first.getShelf() == null && moving.getShelf() == target,
                    "An owning replacement transfers and detaches the appropriate members");
            var edits = recorder.finish().transactions().get(0).operations();
            check(edits.size() == 2, "Owning replacement publishes two V1 edits");
            expectOperation(edits.get(0), "source", "volumes", List.of("c"), List.of());
            expectOperation(edits.get(1), "target", "volumes", List.of("a"), List.of("c"));
        }
    }

    private static ArchiveNode node(String name) { return ArchiveNode.newBuilder().withName(name).build(); }

    private static ReadingRoom owningCheckpoint() {
        var root = ReadingRoom.newInstance();
        var target = Shelf.newBuilder().withName("target").build();
        var source = Shelf.newBuilder().withName("source").build();
        target.getVolumes().addAll(List.of(volume("a"), volume("b")));
        source.getVolumes().add(volume("c"));
        root.getShelves().addAll(List.of(target, source));
        return root;
    }

    private static Volume volume(String name) { return Volume.newBuilder().withName(name).build(); }

    private static JournalBinding binding(String modelId) {
        return new JournalBinding() {
            @Override public String modelId() { return modelId; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject value) {
                if (value instanceof ArchiveNode) return ((ArchiveNode) value).getName();
                if (value instanceof Shelf) return ((Shelf) value).getName();
                if (value instanceof Volume) return ((Volume) value).getName();
                return "room";
            }
            @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
            @Override public VObject create(String type, String id) { throw new AssertionError("Existing objects only"); }
        };
    }

    private static void expectEvent(Change change, String name, List<VObject> removed, List<VObject> added) {
        var edit = change.referenceSetChange().orElseThrow();
        check(change.propertyName().equals(name) && edit.indices().isEmpty()
                && edit.removed().equals(removed) && edit.added().equals(added), "Unindexed native membership payload");
    }

    private static ChangeJournal.Operation only(ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1
                && journal.transactions().get(0).operations().size() == 1, "One V1 membership edit");
        return journal.transactions().get(0).operations().get(0);
    }

    private static void expectOperation(ChangeJournal.Operation edit, String target, String property,
            List<String> removed, List<String> added) {
        check(edit.kind() == ChangeJournal.Kind.REF_SET_EDIT && edit.target().equals(target)
                && edit.property().equals(property) && edit.indices().isEmpty()
                && edit.before().stream().map(ChangeJournal.Value::text).collect(Collectors.toList()).equals(removed)
                && edit.after().stream().map(ChangeJournal.Value::text).collect(Collectors.toList()).equals(added),
                "The V1 edit stores unindexed membership");
    }

    private static void move(VIterator cursor, VObject... expected) {
        for (var value : expected) check(cursor.next() == value, "The cursor selects the expected identity");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
