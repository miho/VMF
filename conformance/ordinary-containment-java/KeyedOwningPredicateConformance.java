package eu.mihosoft.vmf.conformance.ordinarycontainment;

import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
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

final class KeyedOwningPredicateConformance {
    private static final String CHECKPOINT = "keyed-owning-predicate-0";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "keyed-owning-predicate"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            if (model instanceof ReadingRoom) return "room";
            return model instanceof Shelf ? ((Shelf) model).getName() : ((Volume) model).getName();
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private KeyedOwningPredicateConformance() {}

    static void verify(boolean write, Path directory) throws IOException {
        ChangeJournal recorded = null;
        if (write) {
            verifyUndo();
            recorded = record();
        }
        String author = write ? "java" : "csharp";
        for (var format : VMFDocumentFormat.values()) {
            var file = directory.resolve(author + "-keyed-owning-predicate." + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(file, VMFJournalCodec.write(recorded, format));
            var journal = VMFJournalCodec.read(Files.readString(file), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 frame");
            var edits = journal.transactions().get(0).operations();
            check(edits.size() == 2 && edits.get(0).kind() == ChangeJournal.Kind.LIST_REMOVE
                            && edits.get(0).property().equals("shelves") && edits.get(0).indices().equals(List.of(0, 2))
                            && edits.get(1).kind() == ChangeJournal.Kind.SET && edits.get(1).property().equals("featured"),
                    "The keyed list edit precedes its nested direct listener write in V1");
            var original = fixture();
            var replay = JournalReplayer.replay(original, BINDING, CHECKPOINT, journal);
            var survivor = replay.findShelvesByName("kept").orElseThrow();
            check(replay.getShelves().size() == 1 && replay.getShelves().get(0) == survivor
                            && survivor.getVolumes().iterator().next() == replay.getFeatured()
                            && replay.getFeatured().getShelf() == survivor
                            && original.getShelves().size() == 3 && original.getFeatured() == null,
                    "Replay reconstructs keyed lookup and the same owned feature without changing the checkpoint");
        }
    }

    private static void verifyUndo() {
        var room = fixture();
        var shelves = room.getShelves();
        var first = shelves.get(0);
        var kept = shelves.get(1);
        var last = shelves.get(2);
        var events = new ArrayList<String>();
        room.asReadOnly().getShelves().addChangeListener(edit -> events.add("view:" + slots(edit.removed().indices(), edit.added().indices())));
        shelves.addChangeListener(edit -> events.add("mutable:" + slots(edit.removed().indices(), edit.added().indices())));
        var history = room.vmf().changes();
        history.start();
        check(shelves.removeIf(shelf -> shelf != kept), "Two keys are selected");
        history.stop();
        var edit = history.all().stream().filter(change -> change.propertyName().equals("shelves")).findFirst().orElseThrow();
        var removal = List.of("view:[0, 2]/[]", "mutable:[0, 2]/[]");
        var restoration = List.of("view:[]/[0]", "mutable:[]/[0]", "view:[]/[2]", "mutable:[]/[2]");
        check(events.equals(removal) && room.findShelvesByName("first").isEmpty()
                        && room.asReadOnly().findShelvesByName("last").isEmpty(),
                "A keyed predicate batches removals and live lookups see no removed keys");
        events.clear();
        check(edit.isUndoable(), "The original keys may be restored");
        edit.undo();
        check(events.equals(restoration) && room.findShelvesByName("first").orElseThrow() == first
                        && room.asReadOnly().findShelvesByName("last").orElseThrow() == last.asReadOnly(),
                "Valid keyed undo retains projected and mutable callbacks for each original positional run");
        events.clear();
        edit.apply(room);
        check(events.equals(removal), "Redo again reports one sparse removal");

        kept.setName("last");
        events.clear();
        check(!edit.isUndoable(), "A conflict in the second run makes the whole edit unavailable");
        edit.undo();
        check(shelves.size() == 1 && shelves.get(0) == kept && room.findShelvesByName("last").orElseThrow() == kept
                        && events.isEmpty(), "The first run is never partially restored");
        kept.setName("kept");
        last.setName("first");
        check(!edit.isUndoable(), "Detached entries cannot restore duplicate keys against each other");
        edit.undo();
        check(shelves.size() == 1 && shelves.get(0) == kept && events.isEmpty(),
                "Conflicting detached removals remain detached");
        last.setName("last");
        check(edit.isUndoable(), "Repair makes the original key set admissible again");
        edit.undo();
        check(events.equals(restoration) && shelves.get(0) == first && shelves.get(2) == last,
                "A repaired undo restores the same entries with the original callback order");
    }

    private static ChangeJournal record() {
        var room = fixture();
        var kept = room.getShelves().get(1);
        var feature = kept.getVolumes().iterator().next();
        var notifications = new ArrayList<String>();
        room.getShelves().addChangeListener(edit -> {
            check(Arrays.equals(edit.removed().indices(), new int[] {0, 2})
                            && room.getShelves().size() == 1 && room.getShelves().get(0) == kept,
                    "The direct callback sees the committed sparse keyed removal");
            notifications.add("removed");
            room.setFeatured(feature);
        });
        try (var recorder = JournalRecorder.start(room, BINDING, CHECKPOINT, 0)) {
            check(room.getShelves().removeIf(shelf -> shelf != kept) && notifications.equals(List.of("removed"))
                            && room.getFeatured() == feature, "A single direct callback adds a nested feature reference");
            return recorder.finish();
        }
    }

    private static ReadingRoom fixture() {
        var first = Shelf.newBuilder().withName("first").build();
        var kept = Shelf.newBuilder().withName("kept").build();
        var last = Shelf.newBuilder().withName("last").build();
        kept.getVolumes().add(Volume.newBuilder().withName("volume").build());
        return ReadingRoom.newBuilder().withShelves(first, kept, last).build();
    }

    private static String slots(int[] removed, int[] added) {
        return Arrays.toString(removed) + "/" + Arrays.toString(added);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
