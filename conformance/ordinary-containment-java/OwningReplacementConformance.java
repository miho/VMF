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

final class OwningReplacementConformance {
    private static final String CHECKPOINT = "owning-replacement-0";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "ordinary-owning-replacement"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            if (model instanceof LegacyLayout) return "layout";
            return model instanceof LegacyContainer ? ((LegacyContainer) model).getName() : ((LegacyChild) model).getName();
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private OwningReplacementConformance() {}

    static void verify(boolean write, Path directory) throws IOException {
        ChangeJournal recording = null;
        if (write) {
            verifyAliasesAndPreflight();
            verifyEmptyReplacement();
            recording = record();
        }
        String author = write ? "java" : "csharp";
        for (var format : VMFDocumentFormat.values()) {
            var file = directory.resolve(author + "-owning-replace-all." + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(file, VMFJournalCodec.write(recording, format));
            var journal = VMFJournalCodec.read(Files.readString(file), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One replacement frame");
            var edits = journal.transactions().get(0).operations();
            check(edits.size() == 2 && edits.get(0).kind() == ChangeJournal.Kind.LIST_REMOVE
                            && edits.get(0).target().equals("source") && edits.get(0).indices().equals(List.of(0))
                            && edits.get(1).kind() == ChangeJournal.Kind.LIST_SET
                            && edits.get(1).target().equals("target") && edits.get(1).indices().equals(List.of(0, 1, 2)),
                    "V1 records source removal before the full destination replacement");
            var original = fixture();
            checkFinal(JournalReplayer.replay(original, BINDING, CHECKPOINT, journal));
            check(original.getContainers().get(0).getChildren().get(0) == original.getFeatured()
                            && original.getContainers().get(1).getChildren().get(0).getName().equals("leaving"),
                    "Replay leaves the independent checkpoint unchanged");
        }
    }

    private static ChangeJournal record() {
        var working = fixture();
        var source = working.getContainers().get(0);
        var target = working.getContainers().get(1);
        var moving = source.getChildren().get(0);
        var leaving = target.getChildren().get(0);
        var trace = new ArrayList<String>();
        var view = target.asReadOnly().getChildren();
        source.getChildren().addChangeListener(edit -> {
            check(moving.getOwner() == source && target.getChildren().get(0) == moving,
                    "Source callback sees the pending destination and old source owner");
            trace.add("source:" + slots(edit.removed().indices(), edit.added().indices()));
        });
        moving.vmf().changes().addPropertyListener("owner", edit -> trace.add("moving:"
                + (moving.getOwner() == null ? "null" : moving.getOwner().getName())));
        leaving.vmf().changes().addPropertyListener("owner", edit -> trace.add("leaving:"
                + (leaving.getOwner() == null ? "null" : leaving.getOwner().getName())));
        view.addChangeListener(edit -> trace.add("view:" + slots(edit.removed().indices(), edit.added().indices())));
        target.getChildren().addChangeListener(edit -> trace.add("target:" + slots(edit.removed().indices(), edit.added().indices())));
        try (var recorder = JournalRecorder.start(working, BINDING, CHECKPOINT, 0)) {
            target.getChildren().replaceAll(child -> child == leaving ? moving : child);
            check(trace.equals(List.of("source:[0]/[]", "moving:null", "moving:target", "leaving:null",
                            "view:[0, 1, 2]/[0, 1, 2]", "target:[0, 1, 2]/[0, 1, 2]")),
                    "Ordinary replacement preserves transfer and list notification order");
            check(leaving.getOwner() == null, "The displaced child detaches");
            checkFinal(working);
            return recorder.finish();
        }
    }

    private static void verifyAliasesAndPreflight() {
        var alias = child("alias");
        var owner = LegacyContainer.newBuilder().withName("owner").build();
        owner.getChildren().addAll(Arrays.asList(child("placeholder"), null, alias));
        owner.getChildren().set(0, alias);
        int[] visits = {0};
        var history = owner.vmf().changes();
        history.start();
        owner.getChildren().replaceAll(value -> visits[0]++ == 0 ? null : value);
        history.stop();
        check(alias.getOwner() == owner && owner.getChildren().get(2) == alias && history.all().size() == 1,
                "A remaining alias retains ownership");
        var edit = history.all().get(0);
        edit.undo();
        check(owner.getChildren().get(0) == alias && owner.getChildren().get(1) == null
                        && owner.getChildren().get(2) == alias, "Undo restores alias and null positions");
        edit.apply(owner);
        check(owner.getChildren().get(0) == null && alias.getOwner() == owner, "Redo preserves the retained alias");

        var graph = fixture();
        var source = graph.getContainers().get(0);
        var target = graph.getContainers().get(1);
        var moving = source.getChildren().get(0);
        var leaving = target.getChildren().get(0);
        var events = new ArrayList<String>();
        source.getChildren().addChangeListener(value -> events.add("source"));
        target.getChildren().addChangeListener(value -> events.add("target"));
        try {
            target.getChildren().replaceAll(value -> {
                if (value == null) throw new IllegalStateException("transform failed");
                return moving;
            });
            throw new AssertionError("Expected the transform failure.");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().equals("transform failed"), "The original operator error is preserved");
        }
        check(events.isEmpty() && target.getChildren().get(0) == leaving && leaving.getOwner() == target
                        && source.getChildren().get(0) == moving && moving.getOwner() == source,
                "A throwing transform never begins ownership transfer");

        var branch = BuilderCycleNode.newInstance();
        var leaf = BuilderCycleNode.newInstance();
        branch.getChildren().add(leaf);
        reject(() -> branch.getChildren().replaceAll(value -> branch));
        check(branch.getChildren().size() == 1 && branch.getChildren().get(0) == leaf, "Cycle preflight is atomic");

        var room = ReadingRoom.newInstance();
        var first = Shelf.newBuilder().withName("first").build();
        var second = Shelf.newBuilder().withName("second").build();
        room.getShelves().addAll(List.of(first, second));
        room.getShelves().addChangeListener(event -> events.add(slots(event.removed().indices(), event.added().indices())));
        reject(() -> room.getShelves().replaceAll(value -> value == first
                ? Shelf.newBuilder().withName("second").build() : value));
        check(events.isEmpty() && room.findShelvesByName("first").orElseThrow() == first, "Key preflight is atomic");
        room.getShelves().replaceAll(value -> value == first ? second : first);
        check(events.equals(List.of("[0, 1]/[0, 1]")) && room.getShelves().get(0) == second
                        && room.findShelvesByName("second").orElseThrow() == second,
                "A plain keyed replacement retains identity lookup and emits one full range");
    }

    private static void verifyEmptyReplacement() {
        var empty = LegacyContainer.newBuilder().withName("empty").build();
        empty.getChildren().addChangeListener(event -> { throw new AssertionError("Empty replacement was delivered."); });
        try (var recorder = JournalRecorder.start(empty, BINDING, CHECKPOINT, 0)) {
            empty.getChildren().replaceAll(value -> { throw new AssertionError("No slots to transform."); });
            check(empty.vmf().changes().all().isEmpty() && recorder.finish().transactions().isEmpty(),
                    "Empty replacement neither notifies nor poisons V1 recording");
        }
    }

    private static LegacyLayout fixture() {
        var moving = child("moving");
        var source = LegacyContainer.newBuilder().withName("source").withChildren(moving).build();
        var target = LegacyContainer.newBuilder().withName("target").build();
        target.getChildren().addAll(Arrays.asList(child("leaving"), null, child("kept")));
        return LegacyLayout.newBuilder().withFeatured(moving).withContainers(source, target).build();
    }

    private static LegacyChild child(String name) {
        return LegacyChild.newBuilder().withName(name).build();
    }

    private static void checkFinal(LegacyLayout graph) {
        var target = graph.getContainers().get(1);
        var children = target.getChildren();
        check(graph.getContainers().get(0).getChildren().isEmpty() && children.size() == 3
                        && children.get(0) == graph.getFeatured() && graph.getFeatured().getOwner() == target
                        && children.get(1) == null && children.get(2).getName().equals("kept"),
                "Replacement transfers the actual owned alias and retains the untouched slots");
    }

    private static void reject(Runnable edit) {
        try {
            edit.run();
            throw new AssertionError("Invalid replacement was accepted.");
        } catch (IllegalArgumentException expected) { }
    }

    private static String slots(int[] removed, int[] added) {
        return Arrays.toString(removed) + "/" + Arrays.toString(added);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
