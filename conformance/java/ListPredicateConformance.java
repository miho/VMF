package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

final class ListPredicateConformance {
    private static final String CHECKPOINT = "list-predicate-0";
    private static final ObjectMapper SNAPSHOTS = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.list-predicate"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            if (model instanceof CollectionProfile) return "profile";
            if (model instanceof ArchiveRoot) return "archive";
            return ((ArchiveNode) model).getName();
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations."); }
    };

    private ListPredicateConformance() {}

    static void verifyNative() {
        var model = profile();
        var delivered = new ArrayList<String>();
        record(model.getMessages(), "mutable", delivered);
        try (var recorder = JournalRecorder.start(model, BINDING, CHECKPOINT, 0)) {
            try {
                model.getMessages().removeIf(value -> {
                    if (value == null) throw new IllegalStateException("predicate failed");
                    return true;
                });
                throw new AssertionError("The predicate failure was lost.");
            } catch (IllegalStateException expected) {
                check(expected.getMessage().equals("predicate failed"), "Original predicate failure");
            }
            check(!model.getMessages().removeIf(value -> false) && delivered.isEmpty()
                            && model.getMessages().equals(Arrays.asList("same", null, "same", "held", "cut"))
                            && model.vmf().changes().all().isEmpty() && recorder.finish().transactions().isEmpty(),
                    "Predicate failure and no-match emit no edits");
        }

        var phases = model.getPhases();
        var view = model.asReadOnly().getPhases();
        record(view, "view", delivered);
        record(phases, "mutable", delivered);
        var history = model.vmf().changes();
        history.start();
        check(phases.removeIf(value -> !value.equals("keep")), "The checked unique predicate removes two slots");
        history.stop();
        check(delivered.equals(List.of("view:remove[0, 2]", "mutable:remove[0, 2]")) && history.all().size() == 1,
                "One projected and mutable sparse removal is delivered");
        var change = history.all().get(0);
        delivered.clear();
        change.undo();
        check(view.equals(List.of("a", "keep", "c"))
                        && delivered.equals(List.of("view:add[0, 2]", "mutable:add[0, 2]")),
                "Checked unique undo restores both sparse slots together");
        delivered.clear();
        change.apply(model);
        check(view.equals(List.of("keep")) && delivered.equals(List.of("view:remove[0, 2]", "mutable:remove[0, 2]")),
                "Redo reapplies both sparse slots together");
        phases.set(0, "a");
        check(!change.isUndoable(), "Undo rejects a duplicate against the current list");
    }

    static void exchange(Path directory, boolean write) throws IOException {
        var model = profile();
        var archive = archive();
        ChangeJournal scalarJournal = null;
        ChangeJournal referenceJournal = null;
        if (write) {
            SNAPSHOTS.writeValue(directory.resolve("java-predicate-scalars-checkpoint.json").toFile(), model);
            SNAPSHOTS.writeValue(directory.resolve("java-predicate-references-checkpoint.json").toFile(), archive);
            try (var recorder = JournalRecorder.start(model, BINDING, CHECKPOINT, 0)) {
                var shared = model.getMessages().get(0);
                removeAtEnds(model.getMessages(), model.asReadOnly().getMessages());
                check(model.getMessages().get(1) == shared, "Only the selected scalar alias was removed");
                remove(model.getPhases(), model.asReadOnly().getPhases(), value -> !value.equals("keep"), "[0, 2]");
                remove(model.getCounts(), model.asReadOnly().getCounts(), value -> value != 2, "[0, 2]");
                scalarJournal = recorder.finish();
            }
            try (var recorder = JournalRecorder.start(archive, BINDING, CHECKPOINT, 0)) {
                var head = archive.getHead();
                removeAtEnds(head.getTrail(), head.asReadOnly().getTrail());
                check(head.getTrail().get(1) == archive.getReview().get(0), "The remaining model alias is identical");
                referenceJournal = recorder.finish();
            }
        }
        replay(directory, write, "scalars", CollectionProfile.class, scalarJournal, (before, after) -> {
            check(before.getMessages().equals(Arrays.asList("same", null, "same", "held", "cut"))
                            && before.getPhases().equals(List.of("a", "keep", "c"))
                            && before.getCounts().equals(List.of(1, 2, 3)),
                    "Replay leaves the independent scalar checkpoint untouched");
            check(after.getMessages().equals(Arrays.asList(null, "same", "held"))
                            && after.getPhases().equals(List.of("keep")) && after.getCounts().equals(List.of(2)),
                    "Replay restores sparse scalar edits");
        }, List.of(List.of(0, 4), List.of(0, 2), List.of(0, 2)));
        replay(directory, write, "references", ArchiveRoot.class, referenceJournal, (before, after) -> {
            check(before.getHead().getTrail().size() == 5
                            && before.getHead().getTrail().get(0) == before.getReview().get(0),
                    "Replay leaves the independent reference checkpoint untouched");
            var head = after.getHead();
            check(head.getTrail().size() == 3 && head.getTrail().get(0) == null
                            && head.getTrail().get(1) == after.getReview().get(0) && head.getTrail().get(2) == head,
                    "Replay restores the null and model aliases");
        }, List.of(List.of(0, 4)));
    }

    private static <T extends VObject> void replay(Path directory, boolean write, String name, Class<T> model,
            ChangeJournal recorded, BiConsumer<T, T> verify, List<List<Integer>> indices) throws IOException {
        String author = write ? "java" : "csharp";
        var snapshot = directory.resolve(author + "-predicate-" + name + "-checkpoint.json");
        for (var format : VMFDocumentFormat.values()) {
            var file = directory.resolve(author + "-predicate-" + name + "." + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(file, VMFJournalCodec.write(recorded, format));
            var journal = VMFJournalCodec.read(Files.readString(file), format);
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "One V1 frame");
            var edits = journal.transactions().get(0).operations();
            check(edits.size() == indices.size(), "One wire edit per predicate call");
            for (int index = 0; index < edits.size(); index++) {
                var edit = edits.get(index);
                check(edit.kind() == ChangeJournal.Kind.LIST_REMOVE && edit.indices().equals(indices.get(index)),
                        "Predicate V1 edit reports the original sparse positions");
            }
            T original = SNAPSHOTS.readValue(snapshot.toFile(), model);
            verify.accept(original, JournalReplayer.replay(original, BINDING, CHECKPOINT, journal));
        }
    }

    private static <T, R> void removeAtEnds(VList<T> list, VList<R> view) {
        var visited = new ArrayList<T>();
        Predicate<T> atEnds = value -> {
            visited.add(value);
            return visited.size() == 1 || visited.size() == 5;
        };
        remove(list, view, atEnds, "[0, 4]");
        check(visited.size() == 5, "Predicate visits each original slot once");
    }

    private static <T, R> void remove(VList<T> list, VList<R> view, Predicate<T> predicate, String indices) {
        var events = new ArrayList<String>();
        record(view, "view", events);
        record(list, "mutable", events);
        check(list.removeIf(predicate) && events.equals(List.of("view:remove" + indices, "mutable:remove" + indices)),
                "Predicate sends one sparse removal");
    }

    private static <T> void record(VList<T> list, String scope, List<String> changes) {
        list.addChangeListener(edit -> {
            boolean adding = edit.added().indices().length != 0;
            changes.add(scope + (adding ? ":add" : ":remove")
                    + Arrays.toString(adding ? edit.added().indices() : edit.removed().indices()));
        });
    }

    private static CollectionProfile profile() {
        var model = CollectionProfile.newInstance();
        var shared = new String("same");
        model.getMessages().addAll(Arrays.asList(shared, null, shared, "held", "cut"));
        model.getPhases().addAll(List.of("a", "keep", "c"));
        model.getCounts().addAll(List.of(1, 2, 3));
        model.getLevels().add(1.0);
        model.getLimits().put("one", 1L);
        return model;
    }

    private static ArchiveRoot archive() {
        var archive = ArchiveRoot.newInstance();
        var head = ArchiveNode.newBuilder().withName("head").build();
        var alias = ArchiveNode.newBuilder().withName("alias").build();
        var cut = ArchiveNode.newBuilder().withName("cut").build();
        head.getChildren().addAll(List.of(alias, cut));
        head.getTrail().addAll(Arrays.asList(alias, null, alias, head, cut));
        archive.setHead(head);
        archive.getReview().addAll(List.of(alias, cut));
        return archive;
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}
