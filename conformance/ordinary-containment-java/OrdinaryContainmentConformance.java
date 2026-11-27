package eu.mihosoft.vmf.conformance.ordinarycontainment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import vjavax.observer.collection.CollectionChangeEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class OrdinaryContainmentConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "ordinary-containment"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) {
            if (model instanceof LegacyLayout) return "layout";
            return model instanceof LegacyContainer ? ((LegacyContainer) model).getName() : ((LegacyChild) model).getName();
        }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) {
            throw new UnsupportedOperationException("This journal edits existing objects only.");
        }
    };
    private static final JournalBinding MIXED_BINDING = new JournalBinding() {
        @Override public String modelId() { return BINDING.modelId(); }
        @Override public int modelVersion() { return BINDING.modelVersion(); }
        @Override public String id(VObject model) { return BINDING.id(model); }
        @Override public String type(VObject model) { return BINDING.type(model); }
        @Override public VObject create(String type, String id) {
            if (type.equals(LegacyChild.class.getName())) return child(id);
            throw new IllegalArgumentException("Unexpected mixed journal creation: " + type);
        }
    };

    private OrdinaryContainmentConformance() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        boolean write = args[0].equals("write");
        Path exchange = Path.of(args[1]);
        if (write) {
            Files.createDirectories(exchange);
            OwningValidationConformance.verify();
            verifyLiveEdits();
            verifyBuilderTransfers();
            verifyBuilderCycle(false, false);
            verifyBuilderCycle(true, false);
            verifyBuilderCycle(false, true);
            verifyPositionalUndo();
            verifySingleAliasUndo();
            verifyRepeatedGraphPatch();
            verifyRepeatedJournalBoundary();
            verifyRepeatedReferenceLimitation();
            verifyMixedObservers();
            verifyIndexedMixedInsert();
            verifyOwningListIterator();
            verifySublistIterator();
        }
        var journal = write ? recordJournal() : null;
        var aliasUndoJournal = write ? recordAliasUndoJournal(true) : null;
        var reinsertionJournal = write ? recordReinsertionJournal() : null;
        var freshJournal = write ? recordMixedJournal(false) : null;
        var transferJournal = write ? recordMixedJournal(true) : null;
        var sublistJournal = write ? recordSublistJournal() : null;
        var nestedJournal = write ? recordMixedJournalWithNestedChanges(false) : null;
        if (write) verifyNestedMixedJournal(recordMixedJournalWithNestedChanges(true));
        for (var format : VMFDocumentFormat.values()) {
            for (String profile : List.of("plain", "refs", "repeat")) {
                var mapper = mapper(format, !profile.equals("plain"));
                String filename = (write ? "java-" : "csharp-") + profile + "." + format.name().toLowerCase(Locale.ROOT);
                Path file = exchange.resolve(filename);
                if (write) Files.writeString(file, mapper.writeValueAsString(fixtureFor(profile)));
                verifySnapshot(mapper.readValue(file.toFile(), LegacyLayout.class), profile);
            }
            String journalName = (write ? "java-" : "csharp-") + "journal." + format.name().toLowerCase(Locale.ROOT);
            Path log = exchange.resolve(journalName);
            if (write) Files.writeString(log, VMFJournalCodec.write(journal, format));
            var restored = VMFJournalCodec.read(Files.readString(log), format);
            check(restored.wireVersion() == 1, "Ordinary positional edits use the existing version 1 journal");
            verifyReplay(JournalReplayer.replay(fixtureFor("plain"), BINDING, "checkpoint", restored));

            String aliasUndoName = (write ? "java-" : "csharp-") + "alias-undo-journal."
                    + format.name().toLowerCase(Locale.ROOT);
            Path aliasUndoLog = exchange.resolve(aliasUndoName);
            if (write) Files.writeString(aliasUndoLog, VMFJournalCodec.write(aliasUndoJournal, format));
            verifyAliasUndoJournal(VMFJournalCodec.read(Files.readString(aliasUndoLog), format), write);

            String reinsertName = (write ? "java-" : "csharp-") + "reinsert-journal."
                    + format.name().toLowerCase(Locale.ROOT);
            Path reinsertLog = exchange.resolve(reinsertName);
            if (write) Files.writeString(reinsertLog, VMFJournalCodec.write(reinsertionJournal, format));
            verifyReinsertionJournal(VMFJournalCodec.read(Files.readString(reinsertLog), format));

            for (boolean transfer : new boolean[] { false, true }) {
                String mixedName = (write ? "java-" : "csharp-") + (transfer ? "transfer" : "fresh")
                        + "-mixed-reinsert-journal." + format.name().toLowerCase(Locale.ROOT);
                Path mixed = exchange.resolve(mixedName);
                if (write) Files.writeString(mixed, VMFJournalCodec.write(transfer ? transferJournal : freshJournal, format));
                verifyMixedJournal(VMFJournalCodec.read(Files.readString(mixed), format), transfer);
            }
            Path sublist = exchange.resolve((write ? "java-" : "csharp-") + "sublist-reinsert-journal."
                    + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(sublist, VMFJournalCodec.write(sublistJournal, format));
            verifySublistJournal(VMFJournalCodec.read(Files.readString(sublist), format));
            Path nested = exchange.resolve((write ? "java-" : "csharp-") + "nested-mixed-reinsert-journal."
                    + format.name().toLowerCase(Locale.ROOT));
            if (write) Files.writeString(nested, VMFJournalCodec.write(nestedJournal, format));
            verifyNestedMixedJournal(VMFJournalCodec.read(Files.readString(nested), format));
        }
        verifyReentrantOwningLists(write, exchange);
        verifyTransferCallbackJournals(write, exchange);
        verifyReentrantOwningRemovals(write, exchange);
        OwningReplacementConformance.verify(write, exchange);
        OwningPredicateConformance.verify(write, exchange);
        KeyedOwningPredicateConformance.verify(write, exchange);
        System.out.println("Java verified ordinary containment edits, snapshots, and V1 journal replay.");
        SharedInverseConformance.verify(write, exchange);
        UnorderedContainmentConformance.verify(write, exchange);
        ValueMatchingAliasConformance.verify(write, exchange);
    }

    private static void verifyTransferCallbackJournals(boolean write, Path exchange) throws IOException {
        for (String scope : List.of("local", "recursive", "direct")) {
            var journal = write ? recordTransferCallback(scope) : null;
            for (var format : VMFDocumentFormat.values()) {
                var file = exchange.resolve((write ? "java-" : "csharp-") + "transfer-source-" + scope + "-journal."
                        + format.name().toLowerCase(Locale.ROOT));
                if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
                var recorded = VMFJournalCodec.read(Files.readString(file), format);
                var operations = recorded.transactions().get(0).operations();
                check(recorded.wireVersion() == 1 && recorded.transactions().size() == 1 && operations.size() == 3,
                        "V1 keeps one transfer frame with its source callback edit");
                var remove = operations.get(0);
                var replace = operations.get(1);
                var adopt = operations.get(2);
                check(remove.kind() == ChangeJournal.Kind.LIST_REMOVE && remove.target().equals("source")
                                && ids(remove.before()).equals(List.of("moving")) && remove.indices().equals(List.of(0))
                                && replace.kind() == ChangeJournal.Kind.LIST_SET && replace.target().equals("source")
                                && replace.indices().equals(List.of(0)) && ids(replace.before()).equals(List.of("kept"))
                                && ids(replace.after()).equals(List.of("nested"))
                                && adopt.kind() == ChangeJournal.Kind.LIST_ADD && adopt.target().equals("destination")
                                && adopt.indices().equals(List.of(0)) && ids(adopt.after()).equals(List.of("moving")),
                        "journal applies the committed removal before its callback and before adoption");
                var checkpoint = transferCallbackFixture();
                var revised = JournalReplayer.replay(checkpoint, MIXED_BINDING, "checkpoint", recorded);
                var source = revised.getContainers().get(0);
                var destination = revised.getContainers().get(1);
                check(slots(source).equals(List.of("nested")) && source.getChildren().get(0).getOwner() == source
                                && slots(destination).equals(List.of("moving")) && destination.getChildren().get(0).getOwner() == destination
                                && slots(checkpoint.getContainers().get(0)).equals(List.of("moving", "kept")),
                        "causal transfer restores both ordered lists, inverses, and leaves the checkpoint independent");
            }
        }
    }

    private static LegacyLayout transferCallbackFixture() {
        return LegacyLayout.newBuilder().withContainers(
                LegacyContainer.newBuilder().withName("source").withChildren(child("moving"), child("kept")).build(),
                LegacyContainer.newBuilder().withName("destination").build()).build();
    }

    private static ChangeJournal recordTransferCallback(String scope) {
        var working = transferCallbackFixture();
        var source = working.getContainers().get(0);
        var destination = working.getContainers().get(1);
        var moving = source.getChildren().get(0);
        var kept = source.getChildren().get(1);
        var nested = child("nested");
        var trace = new ArrayList<String>();
        Runnable replaceSibling = () -> {
            if (!trace.isEmpty()) return;
            check(source.getChildren().get(0) == kept && moving.getOwner() == source
                            && destination.getChildren().get(0) == moving, "source callback sees the pending adoption");
            trace.add("source");
            source.getChildren().set(0, nested);
            trace.add("nested");
        };
        var sourceObserver = scope.equals("direct") ? source.getChildren().addChangeListener(event -> replaceSibling.run())
                : scope.equals("local") ? source.vmf().changes().addPropertyListener("children", change -> replaceSibling.run())
                : working.vmf().changes().addRecursiveListener(change -> {
                    if (change.object() == source && change.propertyName().equals("children")) replaceSibling.run();
                });
        var inverseObserver = moving.vmf().changes().addPropertyListener("owner", change -> trace.add(
                change.propertyChange().orElseThrow().newValue() == null ? "detach" : "attach"));
        var adoptionObserver = destination.vmf().changes().addPropertyListener("children", change -> trace.add("adopt"));
        try (var recorder = JournalRecorder.start(working, MIXED_BINDING, "checkpoint", 0)) {
            destination.getChildren().add(moving);
            check(trace.equals(List.of("source", "nested", "detach", "attach", "adopt")), "native transfer delivery stages");
            var history = working.vmf().changes().all().stream().filter(change -> change.propertyName().equals("children"))
                    .map(change -> change.listChange().orElseThrow()).collect(Collectors.toList());
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            // A raw list listener edits before any model relay sees the triggering edit, so its edits are recorded
            // first; raw collection listeners are outside the portable contract.
            boolean direct = scope.equals("direct");
            int removal = direct ? 1 : 0;
            int replacement = direct ? 0 : 1;
            check(history.size() == 3 && history.get(replacement).added().elements().get(0) == nested
                            && history.get(removal).removed().elements().get(0) == moving
                            && history.get(2).added().elements().get(0) == moving,
                    "native history orders the transfer's removal, the source callback's replacement, the adoption");
            return recorder.finish();
        } finally {
            sourceObserver.unsubscribe();
            inverseObserver.unsubscribe();
            adoptionObserver.unsubscribe();
        }
    }

    private static void verifyReentrantOwningLists(boolean write, Path exchange) throws IOException {
        verifyFreshOwningChild(write, exchange);
        for (String action : List.of("add", "sort")) {
            for (String scope : List.of("local", "recursive")) {
                boolean sort = action.equals("sort");
                var journal = write ? recordReentrantOwningList(sort, scope.equals("recursive")) : null;
                for (var format : VMFDocumentFormat.values()) {
                    var file = exchange.resolve((write ? "java-" : "csharp-") + "owning-" + action + "-" + scope
                            + "-journal." + format.name().toLowerCase(Locale.ROOT));
                    if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
                    verifyReentrantOwningList(VMFJournalCodec.read(Files.readString(file), format), sort);
                }
            }
        }
    }

    private static void verifyFreshOwningChild(boolean write, Path exchange) throws IOException {
        JournalBinding binding = new JournalBinding() {
            @Override public String modelId() { return "ordinary-containment-observer-init"; }
            @Override public int modelVersion() { return 1; }
            @Override public String id(VObject model) { return model instanceof LegacyContainer ? "root" : "fresh"; }
            @Override public String type(VObject model) { return BINDING.type(model); }
            @Override public VObject create(String type, String id) {
                check(type.equals(LegacyChild.class.getName()), "Expected LegacyChild");
                return child(id);
            }
        };
        for (boolean recursive : new boolean[] { false, true }) {
            var file = exchange.resolve((write ? "java-" : "csharp-") + "fresh-owning-child-"
                    + (recursive ? "recursive" : "local") + ".json");
            if (write) {
                var root = LegacyContainer.newBuilder().withName("root").build();
                var fresh = child("fresh");
                var observer = root.vmf().changes().addListener(change -> {
                    if (!change.propertyName().equals("children")) return;
                    check(fresh.getOwner() == root, "Ownership is already installed for initialization");
                    root.setName("root-edited");
                    fresh.setName("fresh-edited");
                }, recursive);
                try (var recorder = JournalRecorder.start(root, binding, "initial", 0)) {
                    root.getChildren().add(fresh);
                    var history = root.vmf().changes().all();
                    // The triggering edit is recorded before the edits its listeners made (portable contract H4).
                    check(history.size() == 2 && history.get(0).propertyName().equals("children")
                            && history.get(1).propertyName().equals("name"),
                            "Native history records the initiating add first: " + history);
                    Files.writeString(file, VMFJournalCodec.write(recorder.finish(), VMFDocumentFormat.JSON));
                } finally {
                    observer.unsubscribe();
                }
            }
            var journal = VMFJournalCodec.read(Files.readString(file), VMFDocumentFormat.JSON);
            var ops = journal.transactions().get(0).operations();
            check(journal.wireVersion() == 1 && journal.transactions().size() == 1 && ops.size() == 2
                            && ops.get(0).kind() == ChangeJournal.Kind.LIST_ADD && ops.get(0).created().size() == 1
                            && ops.get(1).property().equals("name"),
                    "V1 keeps the owning introduction and recorded owner scalar change in causal order");
            var checkpoint = LegacyContainer.newBuilder().withName("root").build();
            var replay = JournalReplayer.replay(checkpoint, binding, "initial", journal);
            check(replay.getName().equals("root-edited") && slots(replay).equals(List.of("fresh-edited"))
                            && replay.getChildren().get(0).getOwner() == replay && checkpoint.getChildren().isEmpty(),
                    "Fresh child's initialized payload and ownership survive replay");
        }
    }

    private static LegacyContainer causalOwningList() {
        return LegacyContainer.newBuilder().withName("owner")
                .withChildren(child("left"), child("middle"), child("right")).build();
    }

    private static ChangeJournal recordReentrantOwningList(boolean sort, boolean recursive) {
        var owner = causalOwningList();
        var children = owner.getChildren();
        var changes = owner.vmf().changes();
        var outer = sort ? children.get(2) : child("outer");
        var nested = child("nested");
        int index = sort ? 0 : 3;
        boolean[] notified = {false};
        var subscription = changes.addListener(change -> {
            if (notified[0] || change.object() != owner || !change.propertyName().equals("children")) return;
            notified[0] = true;
            check(children.get(index) == outer && outer.getOwner() == owner,
                    "The callback sees the originating add or sort at its installed position");
            children.set(index, nested);
            check(changes.all().size() == 1 && children.get(index) == nested && nested.getOwner() == owner,
                    "Nested edits still reach native history before the originating callback returns");
        }, recursive);
        try (var recorder = JournalRecorder.start(owner, MIXED_BINDING, "checkpoint", 0)) {
            if (sort) children.sort(java.util.Comparator.comparing(LegacyChild::getName).reversed());
            else children.add(outer);
            check(notified[0] && changes.all().size() == 2 && outer.getOwner() == null,
                    "The replaced identity was detached and both native events were retained");
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            var first = changes.all().get(0).listChange().orElseThrow();
            var second = changes.all().get(1).listChange().orElseThrow();
            check(first.added().elements().get(0) == outer
                            && Arrays.equals(first.added().indices(), sort ? new int[] { 0, 2 } : new int[] { 3 })
                            && second.added().elements().get(0) == nested
                            && Arrays.equals(second.added().indices(), new int[] { index }),
                    "Native history records the initiating add or sparse sort before the nested replacement");
            recorder.publishFrame();
            return recorder.finish();
        } finally {
            subscription.unsubscribe();
        }
    }

    private static void verifyReentrantOwningList(ChangeJournal journal, boolean sort) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1,
                "Both owning edits use one existing V1 frame");
        var operations = journal.transactions().get(0).operations();
        check(operations.size() == 2, "Both causal edits belong to the recorded frame");
        var outer = operations.get(0);
        var nested = operations.get(1);
        check(outer.kind() == (sort ? ChangeJournal.Kind.LIST_SET : ChangeJournal.Kind.LIST_ADD)
                        && outer.indices().equals(sort ? List.of(0, 2) : List.of(3))
                        && ids(outer.after()).equals(sort ? List.of("right", "left") : List.of("outer"))
                        && outer.created().size() == (sort ? 0 : 1)
                        && (sort || outer.created().get(0).id().equals("outer")),
                "Portable capture freezes the initiating edit before its callback replaces an occupant");
        check(nested.kind() == ChangeJournal.Kind.LIST_SET && nested.indices().equals(List.of(sort ? 0 : 3))
                        && ids(nested.before()).equals(List.of(sort ? "right" : "outer"))
                        && ids(nested.after()).equals(List.of("nested")) && nested.created().size() == 1,
                "The nested substitution replays against the changed list position");
        var baseline = causalOwningList();
        var restored = JournalReplayer.replay(baseline, MIXED_BINDING, "checkpoint", journal);
        check(slots(restored).equals(sort ? List.of("nested", "middle", "left") : List.of("left", "middle", "right", "nested"))
                        && restored.getChildren().stream().allMatch(child -> child.getOwner() == restored)
                        && slots(baseline).equals(List.of("left", "middle", "right")),
                "Replaying either writer on an independent graph restores all owners and the final order");
    }

    private static void verifyReentrantOwningRemovals(boolean write, Path exchange) throws IOException {
        for (String scope : List.of("local", "recursive")) {
            var journal = write ? recordReentrantOwningRemoval(scope.equals("recursive")) : null;
            for (var format : VMFDocumentFormat.values()) {
                var file = exchange.resolve((write ? "java-" : "csharp-") + "owning-remove-" + scope
                        + "-journal." + format.name().toLowerCase(Locale.ROOT));
                if (write) Files.writeString(file, VMFJournalCodec.write(journal, format));
                var restored = VMFJournalCodec.read(Files.readString(file), format);
                check(restored.wireVersion() == 1 && restored.transactions().size() == 1, "One V1 removal frame");
                var edits = restored.transactions().get(0).operations();
                check(edits.size() == 2, "Both removals are present");
                for (var edit : edits) {
                    check(edit.kind() == ChangeJournal.Kind.LIST_REMOVE && edit.indices().equals(List.of(0))
                                    && edit.target().equals("owner") && edit.property().equals("children")
                                    && edit.after().isEmpty() && edit.created().isEmpty(),
                            "Both removals use the compacted first position without creating values");
                }
                check(ids(edits.get(0).before()).equals(List.of("left"))
                        && ids(edits.get(1).before()).equals(List.of("middle")), "V1 follows the mutation order");
                var checkpoint = causalOwningList();
                var result = JournalReplayer.replay(checkpoint, MIXED_BINDING, "checkpoint", restored);
                check(slots(result).equals(List.of("right")) && result.getChildren().get(0).getOwner() == result,
                        "The retained child belongs to the replayed owner");
                check(slots(checkpoint).equals(List.of("left", "middle", "right")), "Replay leaves the checkpoint intact");
            }
        }
    }

    private static ChangeJournal recordReentrantOwningRemoval(boolean recursive) {
        var owner = causalOwningList();
        var children = owner.getChildren();
        var first = children.get(0);
        var middle = children.get(1);
        var trace = new ArrayList<String>();
        var changes = owner.vmf().changes();
        var listener = changes.addListener(change -> {
            if (change.object() != owner || !change.propertyName().equals("children")) return;
            var removed = (LegacyChild) change.listChange().orElseThrow().removed().elements().get(0);
            trace.add(removed.getName());
            if (removed == first) children.remove(0);
        }, recursive);
        try (var recorder = JournalRecorder.start(owner, MIXED_BINDING, "checkpoint", 0)) {
            children.remove(0);
            check(trace.equals(List.of("left", "middle")), "Owning callbacks remain synchronous");
            check(first.getOwner() == null && middle.getOwner() == null && slots(owner).equals(List.of("right")),
                    "Both exact removed children detach from the live owner");
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            check(changes.all().size() == 2
                            && changes.all().get(0).listChange().orElseThrow().removed().elements().get(0) == first
                            && changes.all().get(1).listChange().orElseThrow().removed().elements().get(0) == middle,
                    "Native history records the initiating removal first");
            return recorder.finish();
        } finally {
            listener.unsubscribe();
        }
    }

    private static LegacyChild child(String name) {
        return LegacyChild.newBuilder().withName(name).build();
    }

    private static LegacyLayout mixedFixture(boolean transfer) {
        var destination = LegacyContainer.newBuilder().withName("destination").build();
        destination.getChildren().addAll(List.of(child("a"), child("b")));
        var source = LegacyContainer.newBuilder().withName("source").build();
        if (transfer) source.getChildren().add(child("c"));
        return LegacyLayout.newBuilder().withContainers(destination, source).build();
    }

    private static LegacyLayout sublistFixture() {
        var graph = mixedFixture(false);
        graph.getContainers().get(0).getChildren().add(child("d"));
        return graph;
    }

    private static ChangeJournal recordSublistJournal() {
        var graph = sublistFixture();
        var owner = graph.getContainers().get(0);
        var children = owner.getChildren();
        var a = children.get(0);
        var b = children.get(1);
        var d = children.get(2);
        var c = child("c");
        var ancestor = children.subList(0, 2);
        var view = ancestor.subList(1, 2);
        var callbacks = new ArrayList<Integer>();
        var subscription = children.addChangeListener(change -> {
            check(view.equals(List.of(b, c, a)) && ancestor.equals(view) && !view.contains(d),
                    "Both nested view bounds must follow the normalized slots before callbacks");
            check(a.getOwner() == owner && b.getOwner() == owner && c.getOwner() == owner,
                    "The view observer sees the stable owners of all installed entries");
            callbacks.add(change.added().elements().size());
        });
        try (var recorder = JournalRecorder.start(graph, MIXED_BINDING, "checkpoint", 0)) {
            view.addAll(List.of(c, a));
            check(callbacks.equals(List.of(3)) && children.get(3) == d && view.size() == 3,
                    "The partial view excludes the untouched suffix and emits one root event");
            return recorder.finish();
        } finally {
            subscription.unsubscribe();
        }
    }

    private static void verifySublistJournal(ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1,
                "A nested Java view and a CLR root insertion use the same V1 group");
        var operations = journal.transactions().get(0).operations();
        check(operations.size() == 2, "The untouched suffix is outside the portable edit");
        var remove = operations.get(0);
        var add = operations.get(1);
        check(remove.kind() == ChangeJournal.Kind.LIST_REMOVE && remove.indices().equals(List.of(0, 1))
                        && ids(remove.before()).equals(List.of("a", "b")) && add.kind() == ChangeJournal.Kind.LIST_ADD
                        && add.indices().equals(List.of(0, 1, 2)) && ids(add.after()).equals(List.of("b", "c", "a"))
                        && add.created().size() == 1 && add.created().get(0).id().equals("c"),
                "The translated ranges use root coordinates and introduce c only once");
        var checkpoint = sublistFixture();
        var restored = JournalReplayer.replay(checkpoint, MIXED_BINDING, "checkpoint", journal);
        var owner = restored.getContainers().get(0);
        check(slots(owner).equals(List.of("b", "c", "a", "d")) && owner.getChildren().get(3).getOwner() == owner
                        && slots(checkpoint.getContainers().get(0)).equals(List.of("a", "b", "d")),
                "V1 retains the untouched suffix on the independent graph");
    }

    private static void verifySublistIterator() {
        var owner = sublistFixture().getContainers().get(0);
        var children = owner.getChildren();
        var a = children.get(0);
        var b = children.get(1);
        var view = children.subList(1, 2);
        var cursor = view.listIterator(1);
        cursor.add(a);
        check(view.equals(List.of(b, a)) && cursor.nextIndex() == 2 && !cursor.hasNext() && cursor.previous() == a,
                "A view iterator tracks its absolute gap while the view grows across a relocated identity");
        cursor.remove();
        check(view.equals(List.of(b)) && cursor.nextIndex() == 1 && a.getOwner() == null
                        && slots(owner).equals(List.of("b", "d")),
                "A following cursor removal acts on the rebased view and retains the unchanged suffix");

        var empty = children.subList(1, 1);
        var insertion = empty.listIterator();
        insertion.add(b);
        check(empty.equals(List.of(b)) && insertion.previous() == b && slots(owner).equals(List.of("b", "d")),
                "A silent owning reinsert can expand an empty view without changing root membership");
    }

    private static ChangeJournal recordMixedJournal(boolean transfer) {
        var graph = mixedFixture(transfer);
        var owner = graph.getContainers().get(0);
        var source = graph.getContainers().get(1);
        var a = owner.getChildren().get(0);
        var c = transfer ? source.getChildren().get(0) : child("c");
        try (var recorder = JournalRecorder.start(graph, MIXED_BINDING, "checkpoint", 0)) {
            owner.getChildren().addAll(List.of(c, a));
            check(slots(owner).equals(List.of("b", "c", "a")) && c.getOwner() == owner && a.getOwner() == owner,
                    "Mixed bulk insertion retains the existing edge and installs the incoming edge");
            recorder.publishFrame();
            return recorder.finish();
        }
    }

    private static ChangeJournal recordMixedJournalWithNestedChanges(boolean direct) {
        var graph = mixedFixture(false);
        var owner = graph.getContainers().get(0);
        var children = owner.getChildren();
        var history = graph.vmf().changes();
        var a = children.get(0);
        var c = child("c");
        var d = child("d");
        var failure = new IllegalStateException("nested source observer");
        var observations = new ArrayList<String>();
        Consumer<CollectionChangeEvent<?, ?, ?>> observe = event -> {
            if (event.added().elements().size() != 3) return;
            observations.add("before nested edits");
            check(slots(owner).equals(List.of("b", "c", "a")) && c.getOwner() == owner,
                    "The outer callback observes the original mixed edit and repaired owner");
            children.set(1, d);
            children.add(null);
            observations.add("after nested edits");
            check(history.all().size() == 2, "Nested changes reach history while the outer callback is still delivering");
            throw failure;
        };
        var subscription = direct ? children.addChangeListener(observe::accept)
                : history.addListener(change -> {
                    if (change.object() != owner) return;
                    change.listChange().ifPresent(observe);
                }, true);
        try (var recorder = JournalRecorder.start(graph, MIXED_BINDING, "checkpoint", 0)) {
            try {
                children.addAll(List.of(c, a));
                throw new AssertionError("Synchronous observer failure should reach the caller");
            } catch (IllegalStateException actual) {
                check(actual == failure, "The recorder leaves the nested observer failure unchanged");
            }
            check(observations.equals(List.of("before nested edits", "after nested edits"))
                            && slots(owner).equals(List.of("b", "d", "a", "null")) && c.getOwner() == null,
                    "Observer changes remain applied after the initiating callback throws");
            // The triggering edit is recorded before the edits its listeners made (portable contract H4).
            // A raw list listener edits before any model relay sees the triggering edit, so its edits are recorded
            // first; raw collection listeners are outside the portable contract.
            int range = direct ? 2 : 0;
            int replacement = direct ? 0 : 1;
            int append = direct ? 1 : 2;
            check(history.all().size() == 3
                            && history.all().get(range).listChange().orElseThrow().added().elements().get(1) == c
                            && history.all().get(replacement).listChange().orElseThrow().added().elements().get(0) == d
                            && history.all().get(append).listChange().orElseThrow().added().elements().get(0) == null,
                    "Native history orders the initiating range and the nested replacement and append");
            recorder.publishFrame();
            return recorder.finish();
        } finally {
            subscription.unsubscribe();
        }
    }

    private static void verifyNestedMixedJournal(ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1,
                "Nested edits and the initiating range belong to one V1 frame");
        var operations = journal.transactions().get(0).operations();
        check(operations.size() == 4, "V1 holds two initiating operations and two nested operations");
        var remove = operations.get(0);
        var add = operations.get(1);
        var replace = operations.get(2);
        var append = operations.get(3);
        check(remove.kind() == ChangeJournal.Kind.LIST_REMOVE && remove.indices().equals(List.of(0, 1))
                        && ids(remove.before()).equals(List.of("a", "b")) && remove.created().isEmpty()
                        && add.kind() == ChangeJournal.Kind.LIST_ADD && add.indices().equals(List.of(0, 1, 2))
                        && ids(add.after()).equals(List.of("b", "c", "a")) && add.created().size() == 1
                        && add.created().get(0).id().equals("c"),
                "The first recorded range captures c before the public callback removes it");
        check(replace.kind() == ChangeJournal.Kind.LIST_SET && replace.indices().equals(List.of(1))
                        && ids(replace.before()).equals(List.of("c")) && ids(replace.after()).equals(List.of("d"))
                        && replace.created().size() == 1 && replace.created().get(0).id().equals("d")
                        && append.kind() == ChangeJournal.Kind.LIST_ADD && append.indices().equals(List.of(3))
                        && append.after().get(0).kind() == ChangeJournal.ValueKind.NULL,
                "The nested replacement and append apply after the initiating range");
        var checkpoint = mixedFixture(false);
        var restored = JournalReplayer.replay(checkpoint, MIXED_BINDING, "checkpoint", journal);
        var owner = restored.getContainers().get(0);
        check(slots(owner).equals(List.of("b", "d", "a", "null")) && owner.getChildren().get(1).getOwner() == owner
                        && slots(checkpoint.getContainers().get(0)).equals(List.of("a", "b")),
                "Portable replay restores the nested edit on an independent graph");
    }

    private static void verifyMixedJournal(ChangeJournal journal, boolean transfer) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1,
                "Mixed ordinary containment still uses one V1 frame");
        var edits = journal.transactions().get(0).operations();
        check(edits.size() == (transfer ? 3 : 2), "Exactly one source removal precedes the normalized destination edit");
        if (transfer) {
            var source = edits.get(0);
            check(source.kind() == ChangeJournal.Kind.LIST_REMOVE && source.target().equals("source")
                            && source.indices().equals(List.of(0)) && source.before().get(0).text().equals("c"),
                    "The transferred identity is first removed from its real source slot");
        }
        var remove = edits.get(edits.size() - 2);
        var add = edits.get(edits.size() - 1);
        check(remove.kind() == ChangeJournal.Kind.LIST_REMOVE && remove.target().equals("destination")
                        && remove.indices().equals(List.of(0, 1)) && ids(remove.before()).equals(List.of("a", "b"))
                        && add.kind() == ChangeJournal.Kind.LIST_ADD && add.target().equals("destination")
                        && add.indices().equals(List.of(0, 1, 2)) && ids(add.after()).equals(List.of("b", "c", "a"))
                        && remove.created().isEmpty() && add.created().size() == (transfer ? 0 : 1),
                "V1 uses final valid positions and introduces a fresh identity only at the destination addition");
        var checkpoint = mixedFixture(transfer);
        var replayed = JournalReplayer.replay(checkpoint, MIXED_BINDING, "checkpoint", journal);
        var owner = replayed.getContainers().get(0);
        check(slots(owner).equals(List.of("b", "c", "a")) && replayed.getContainers().get(1).getChildren().isEmpty()
                        && owner.getChildren().stream().allMatch(entry -> entry.getOwner() == owner)
                        && slots(checkpoint.getContainers().get(0)).equals(List.of("a", "b")),
                "V1 reconstructs both ownerships on an independent checkpoint");
    }

    private static List<String> ids(List<ChangeJournal.Value> values) {
        return values.stream().map(ChangeJournal.Value::text).collect(Collectors.toList());
    }

    private static void verifyIndexedMixedInsert() {
        var graph = mixedFixture(false);
        var owner = graph.getContainers().get(0);
        var a = owner.getChildren().get(0);
        var fresh = child("c");
        try (var recorder = JournalRecorder.start(graph, MIXED_BINDING, "checkpoint", 0)) {
            owner.getChildren().addAll(new int[] { 3, 2 }, List.of(a, fresh));
            verifyMixedJournal(recorder.finish(), false);
        }
        check(slots(owner).equals(List.of("b", "c", "a")) && fresh.getOwner() == owner && a.getOwner() == owner,
                "Indexed insertion preserves original position-value pairs and normalizes coalesced slots");
        try {
            owner.getChildren().addAll(new int[] { 0, 0 }, List.of(child("invalid"), a));
            throw new AssertionError("Duplicate insertion positions must fail before changing the destination");
        } catch (IllegalArgumentException expected) {
            check(slots(owner).equals(List.of("b", "c", "a")), "Invalid indexed batches leave slots unchanged");
        }
    }

    private static void verifyMixedObservers() {
        var graph = mixedFixture(true);
        var owner = graph.getContainers().get(0);
        var source = graph.getContainers().get(1);
        var a = owner.getChildren().get(0);
        var c = source.getChildren().get(0);
        var contender = LegacyContainer.newBuilder().withName("contender").build();
        var events = new ArrayList<String>();
        var failure = new IllegalStateException("source observer");
        source.getChildren().addChangeListener(change -> {
            events.add("source");
            check(slots(owner).equals(List.of("b", "c", "a")) && c.getOwner() == source && a.getOwner() == owner,
                    "Source callback sees staged final destination slots, old incoming owner, and retained owner");
            try {
                source.getChildren().add(c);
                throw new AssertionError("A callback must not compete for the pending attachment");
            } catch (IllegalArgumentException expected) { /* reserved by the outer edit */ }
            try {
                contender.getChildren().add(a);
                throw new AssertionError("A callback must not steal a retained identity from the final slots");
            } catch (IllegalArgumentException expected) { /* the destination still owns this edit */ }
            check(contender.getChildren().isEmpty(), "Rejected transfer has no partial destination effect");
            throw failure;
        });
        source.getChildren().addChangeListener(change -> events.add("source-later"));
        var readonly = owner.asReadOnly().getChildren();
        readonly.addChangeListener(change -> {
            if (!change.wasSet()) return;
            check(change.source() == readonly && change.removed().elements().get(0) == a.asReadOnly()
                            && change.added().elements().get(1) == c.asReadOnly(),
                    "Read-only observation projects both sides onto the same stable views");
            events.add("readonly");
        });
        var children = owner.getChildren();
        children.addChangeListener(change -> {
            if (!change.wasSet()) {
                events.add("nested");
                return;
            }
            events.add("destination");
            check(change.source() == children && c.getOwner() == owner && a.getOwner() == owner
                            && Arrays.equals(change.removed().indices(), new int[] { 0, 1 })
                            && Arrays.equals(change.added().indices(), new int[] { 0, 1, 2 }),
                    "The destination has one stable unequal replacement with correct independent indices");
            children.add(null);
        });
        try {
            children.addAll(List.of(c, a));
            throw new AssertionError("The source failure must reach the caller after repair");
        } catch (IllegalStateException thrown) {
            check(thrown == failure, "The callback error is preserved");
        }
        check(events.equals(List.of("source", "source-later", "readonly", "destination", "nested"))
                        && slots(owner).equals(List.of("b", "c", "a", "null")),
                "All listeners run with correct ownership and nested delivery: " + events);

        var undoGraph = mixedFixture(false);
        var undoOwner = undoGraph.getContainers().get(0);
        var undoChanges = undoGraph.vmf().changes();
        var fresh = child("c");
        var retained = undoOwner.getChildren().get(0);
        undoChanges.start();
        undoOwner.getChildren().addAll(List.of(fresh, retained));
        undoChanges.stop();
        check(undoChanges.all().size() == 1, "One live unequal replacement belongs to native history");
        var edit = undoChanges.all().get(0);
        check(edit.isUndoable(), "The unequal replacement has a native undo path");
        edit.undo();
        check(slots(undoOwner).equals(List.of("a", "b")) && retained.getOwner() == undoOwner && fresh.getOwner() == null,
                "Undo detaches only the introduced identity and restores exact positions");
        edit.apply(undoOwner);
        check(slots(undoOwner).equals(List.of("b", "c", "a")) && fresh.getOwner() == undoOwner,
                "Applying the original live change restores the normalized edit");

        var sequential = mixedFixture(false).getContainers().get(0);
        var sequentialEvents = new ArrayList<String>();
        sequential.getChildren().addChangeListener(change -> sequentialEvents.add(slots(sequential).toString()));
        sequential.getChildren().add(child("c"));
        sequential.getChildren().add(sequential.getChildren().get(0));
        check(sequentialEvents.equals(List.of("[a, b, c]", "[b, c, a]")),
                "Sequential inserts retain their two real callback states");
    }

    private static ChangeJournal recordReinsertionJournal() {
        var graph = fixtureFor("plain");
        var owner = graph.getContainers().get(0);
        var children = owner.getChildren();
        var child = children.get(1);
        var events = new ArrayList<String>();
        var direct = children.addChangeListener(change -> {
            check(change.source() == children && child.getOwner() == owner, "Reinsert retains the owning link in the callback");
            events.add(change.wasSet() + ":" + Arrays.toString(change.removed().indices()) + ":"
                    + Arrays.toString(change.added().indices()));
        });
        try (var recorder = JournalRecorder.start(graph, BINDING, "checkpoint", 0)) {
            var beginning = children.listIterator(0);
            beginning.add(child);
            check(events.isEmpty(), "An insertion that leaves all positions unchanged is silent");
            check(beginning.nextIndex() == 0 && beginning.next() == null, "A silent reinsert keeps a valid iterator gap");
            var end = children.listIterator(children.size());
            end.add(child);
            check(end.nextIndex() == children.size() && !end.hasNext() && end.previous() == child,
                    "The reinserted child precedes the iterator's actual final gap");
            check(events.equals(List.of("true:[1, 2]:[1, 2]")) && slots(owner).equals(List.of("null", "a", "b")),
                    "Reinsert publishes one replacement with actual positions: " + events);
            return recorder.finish();
        } finally {
            direct.unsubscribe();
        }
    }

    private static void verifyOwningListIterator() {
        var graph = mixedFixture(false);
        var owner = graph.getContainers().get(0);
        var children = owner.getChildren();
        var a = children.get(0);
        var d = child("d");
        var cursor = children.listIterator(1);
        cursor.add(d);
        check(cursor.nextIndex() == 2 && cursor.previous() == d && d.getOwner() == owner,
                "A fresh insertion advances one gap and installs its owner");
        cursor.remove();
        check(cursor.nextIndex() == 1 && d.getOwner() == null, "Iterator removal restores the adjacent gap and detaches the child");
        cursor.next();
        cursor.set(a);
        check(children.get(0) == a && children.get(1) == a && a.getOwner() == owner,
                "Iterator replacement still allows the same owning identity in two slots");
        cursor.remove();
        check(children.size() == 1 && children.get(0) == a && a.getOwner() == owner,
                "Removing one alias leaves the other owning occurrence attached");
        children.add(d);
        try {
            cursor.add(a);
            throw new AssertionError("A stale iterator must not perform a normalized reinsert");
        } catch (java.util.ConcurrentModificationException expected) {
            check(slots(owner).equals(List.of("a", "d")), "Rejected stale writes leave the visible slots unchanged");
        }
    }

    private static void verifyReinsertionJournal(ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1,
                "An ordinary reinsert preserves the existing V1 grammar");
        var operations = journal.transactions().get(0).operations();
        check(operations.size() == 1 && operations.get(0).kind() == ChangeJournal.Kind.LIST_SET
                        && operations.get(0).indices().equals(List.of(1, 2)),
                "Both runtimes record the same actual reordered positions");
        var edit = operations.get(0);
        check(edit.before().stream().map(ChangeJournal.Value::text).collect(Collectors.toList()).equals(List.of("b", "a"))
                        && edit.after().stream().map(ChangeJournal.Value::text).collect(Collectors.toList()).equals(List.of("a", "b")),
                "The V1 record identifies the original before and after occupants");
        var baseline = fixtureFor("plain");
        var replayed = JournalReplayer.replay(baseline, BINDING, "checkpoint", journal);
        var owner = replayed.getContainers().get(0);
        check(slots(owner).equals(List.of("null", "a", "b"))
                        && slots(baseline.getContainers().get(0)).equals(List.of("null", "b", "a"))
                        && owner.getChildren().get(1).getOwner() == owner && owner.getChildren().get(2).getOwner() == owner,
                "Portable replay restores the reorder on an independent graph with inverses intact");
    }

    private static ChangeJournal recordJournal() {
        var graph = fixtureFor("plain");
        var source = graph.getContainers().get(0);
        var target = graph.getContainers().get(1);
        var child = source.getChildren().get(2);
        try (var recorder = JournalRecorder.start(graph, BINDING, "checkpoint", 0)) {
            source.getChildren().set(0, child);
            target.getChildren().add(child);
            source.getChildren().add(null);
            verifyReplay(graph);
            return recorder.finish();
        }
    }

    private static void verifyReplay(LegacyLayout graph) {
        var source = graph.getContainers().get(0);
        var target = graph.getContainers().get(1);
        check(slots(source).equals(List.of("b", "null")) && slots(target).equals(List.of("a"))
                        && source.getChildren().get(0).getOwner() == source && target.getChildren().get(0).getOwner() == target,
                "Replay restores ordered null slots and the transferred child's exact inverse");
    }

    private static ChangeJournal recordAliasUndoJournal(boolean removeAliasBeforeCommit) {
        var graph = fixtureFor("repeat");
        var source = graph.getContainers().get(0);
        var changes = graph.vmf().changes();
        changes.start();
        transferFirstChild(graph);
        changes.stop();
        var transfer = changes.transactions().get(0);
        try (var recorder = JournalRecorder.start(graph, BINDING, "checkpoint", 0)) {
            transfer.undo();
            if (removeAliasBeforeCommit) source.getChildren().remove(0);
            return recorder.finish();
        }
    }

    private static void verifyRepeatedGraphPatch() {
        var owner = fixtureFor("plain").getContainers().get(0);
        var middle = owner.getChildren().get(1);
        var repeated = owner.getChildren().get(2);
        var desired = owner.clone();
        desired.getChildren().set(0, desired.getChildren().get(2));

        var patch = ModelDiff.diff(owner, desired);
        patch.forEach(change -> change.apply(change.object()));
        check(owner.getChildren().size() == 3 && owner.getChildren().get(0) == repeated
                        && owner.getChildren().get(2) == repeated && repeated.getOwner() == owner,
                "Graph patch keeps both owning slots on the retained child");
        for (int i = patch.size() - 1; i >= 0; i--) {
            var change = patch.get(i);
            check(change.isUndoable(), "Repeated graph patch is undoable");
            change.undo();
        }
        check(owner.getChildren().size() == 3 && owner.getChildren().get(0) == null
                        && owner.getChildren().get(1) == middle && owner.getChildren().get(2) == repeated
                        && repeated.getOwner() == owner,
                "Graph patch undo restores the original positions and identities");
    }

    private static void verifyRepeatedJournalBoundary() {
        try {
            recordAliasUndoJournal(false);
        } catch (IllegalStateException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null && cause.getMessage().contains("Duplicate object ID or containment entry")) return;
            }
            throw failure;
        }
        throw new AssertionError("V1 cannot publish a frame with duplicate containment entries");
    }

    private static void verifyAliasUndoJournal(ChangeJournal journal, boolean sparseInsert) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1,
                "The alias exists only inside one V1 frame");
        var operations = journal.transactions().get(0).operations();
        var remove = ChangeJournal.Kind.LIST_REMOVE;
        var add = ChangeJournal.Kind.LIST_ADD;
        var expected = sparseInsert ? List.of(remove, add, remove) : List.of(remove, remove, add, remove);
        check(operations.stream().map(ChangeJournal.Operation::kind).collect(Collectors.toList()).equals(expected),
                "Exact alias restoration uses each runtime's existing positional operation kinds");
        var insertion = operations.get(operations.size() - 2);
        check(insertion.indices().equals(sparseInsert ? List.of(0, 2) : List.of(0, 1, 2))
                        && insertion.after().get(0).equals(insertion.after().get(insertion.after().size() - 1)),
                "Both restored positions refer to the original child ID");
        var checkpoint = fixtureFor("repeat");
        transferFirstChild(checkpoint);
        var restored = JournalReplayer.replay(checkpoint, BINDING, "checkpoint", journal);
        var owner = restored.getContainers().get(0);
        check(slots(owner).equals(List.of("b", "a")) && owner.getChildren().get(1).getOwner() == owner
                        && restored.getContainers().get(1).getChildren().isEmpty(),
                "Replay keeps the retained alias and its owner after the temporary alias is removed");
    }

    private static void transferFirstChild(LegacyLayout graph) {
        var source = graph.getContainers().get(0);
        graph.getContainers().get(1).getChildren().add(source.getChildren().get(0));
    }

    private static void verifyLiveEdits() {
        var graph = fixtureFor("repeat");
        var source = graph.getContainers().get(0);
        var target = graph.getContainers().get(1);
        var child = source.getChildren().get(0);
        check(source.getChildren().get(2) == child && child.getOwner() == source,
                "A set edit can hold repeated identities in the same owning field");

        var changes = graph.vmf().changes();
        var observed = new ArrayList<String>();
        var subscription = changes.addRecursiveListener(change -> {
            if (!(change.object() instanceof LegacyContainer) || change.listChange().isEmpty()) return;
            var owner = (LegacyContainer) change.object();
            var delta = change.listChange().orElseThrow();
            observed.add(owner.getName() + ":" + Arrays.toString(delta.removed().indices())
                    + ":" + Arrays.toString(delta.added().indices()));
        });
        changes.start();
        changes.startTransaction();
        target.getChildren().add(child);
        changes.publishTransaction();
        changes.stop();
        subscription.unsubscribe();
        check(observed.equals(List.of("source:[0, 2]:[]", "target:[]:[0]")), "Sparse transfer changes: " + observed);
        check(changes.all().size() == 2 && slots(source).equals(List.of("b")) && child.getOwner() == target,
                "A transfer removes every previous occurrence and gives the child one new owner");
        var transfer = changes.transactions().get(0);
        var restorations = new ArrayList<Change>();
        var restorationObserver = source.vmf().changes().addListener(change -> {
            if (change.listChange().isPresent()) restorations.add(change);
        }, false);
        try {
            transfer.undo();
        } finally {
            restorationObserver.unsubscribe();
        }
        check(source.getChildren().size() == 3 && source.getChildren().get(0) == child
                        && source.getChildren().get(2) == child && slots(source).equals(List.of("a", "b", "a"))
                        && child.getOwner() == source && target.getChildren().isEmpty(),
                "Undo restores both recorded slots with the same child and repairs its inverse");
        check(restorations.size() == 1, "Undo publishes one sparse addition");
        var restoration = restorations.get(0);
        check(Arrays.equals(restoration.listChange().orElseThrow().added().indices(), new int[]{0, 2}),
                "The sparse addition reports both restored positions");
        restoration.undo();
        restoration.apply(source);
        check(slots(source).equals(List.of("a", "b", "a")) && source.getChildren().get(0) == source.getChildren().get(2),
                "Reapplying the native positional addition restores both aliases");

        for (var change : transfer.changes()) change.apply(change.object());
        check(slots(source).equals(List.of("b")) && target.getChildren().get(0) == child && child.getOwner() == target,
                "The native edits replay forward after exact undo");

        var ordered = fixtureFor("plain").getContainers().get(0);
        ordered.getChildren().add(ordered.getChildren().get(1));
        check(slots(ordered).equals(List.of("null", "a", "b")),
                "Adding an existing member at the end moves the existing occurrence");

        var bulk = fixtureFor("plain").getContainers().get(0);
        var children = bulk.getChildren();
        var orderEdits = new ArrayList<Boolean>();
        var observer = children.addChangeListener(change -> orderEdits.add(change.wasSet()));
        try {
            children.addAll(List.of(children.get(2), children.get(1)));
        } finally {
            observer.unsubscribe();
        }
        check(orderEdits.equals(List.of(true)) && slots(bulk).equals(List.of("null", "a", "b")),
                "Bulk reinsertion of existing children is one permutation");
    }

    private static void verifySingleAliasUndo() {
        var owner = fixtureFor("repeat").getContainers().get(0);
        var child = owner.getChildren().get(0);
        var changes = owner.vmf().changes();
        changes.start();
        owner.getChildren().remove(0);
        changes.stop();
        check(child.getOwner() == owner && owner.getChildren().get(1) == child,
                "Removing one alias keeps the child's owner through the other slot");
        changes.all().get(0).undo();
        check(owner.getChildren().size() == 3 && owner.getChildren().get(0) == child
                        && owner.getChildren().get(2) == child && child.getOwner() == owner,
                "Undo restores a removed alias without coalescing the retained slot");
    }

    private static void verifyRepeatedReferenceLimitation() throws IOException {
        var graph = fixtureFor("repeat");
        graph.setFeatured(graph.getContainers().get(0).getChildren().get(0));
        var io = mapper(VMFDocumentFormat.JSON, true);
        String duplicateIds = io.writeValueAsString(graph);
        try {
            io.readValue(duplicateIds, LegacyLayout.class);
        } catch (JsonProcessingException expected) {
            return;
        }
        throw new AssertionError("The reference-aware reader unexpectedly accepted repeated definitions with the same ID");
    }

    private static void verifyPositionalUndo() {
        var owner = LegacyContainer.newBuilder().withName("before").build();
        var original = LegacyChild.newBuilder().withName("original").build();
        var replacement = LegacyChild.newBuilder().withName("replacement").build();
        var unrelated = LegacyChild.newBuilder().withName("unrelated").build();
        owner.getChildren().add(original);

        var changes = owner.vmf().changes();
        changes.start();
        owner.getChildren().set(0, replacement);
        owner.setName("after");
        changes.publishTransaction();
        changes.stop();
        var group = changes.transactions().get(0);
        check(group.changes().size() == 2 && group.isUndoable(), "The published replacement and name group can undo");

        var observed = new ArrayList<String>();
        var observer = changes.addListener(change -> {
            observed.add(change.propertyName());
            if ("name".equals(change.propertyName())) owner.getChildren().add(unrelated);
        }, false);
        try {
            group.undo();
        } finally {
            observer.unsubscribe();
        }
        check(slots(owner).equals(List.of("original", "unrelated"))
                        && original.getOwner() == owner && unrelated.getOwner() == owner && replacement.getOwner() == null,
                "An unrelated append during undo preserves the earlier replacement's eligibility and ownership");
        check(observed.equals(List.of("name", "children", "children")) && changes.all().size() == 2,
                "Undo delivers the reentrant append before the replacement without changing the published history");

        for (var edit : group.changes()) edit.apply(owner);
        check(slots(owner).equals(List.of("replacement", "unrelated")) && owner.getName().equals("after"),
                "Reapplying a positional replacement preserves the unrelated tail");
        owner.getChildren().set(0, original);
        check(!group.isUndoable(), "A changed affected slot prevents undo of the whole group");
        group.undo();
        check(owner.getName().equals("after"), "The stale group leaves the unaffected name edit applied");

        changes.clear();
        changes.start();
        owner.getChildren().add(replacement);
        changes.stop();
        var insertion = changes.all().get(0);
        owner.getChildren().add(null);
        check(insertion.isUndoable(), "Insertion undo still matches its slot after a later append");
        insertion.undo();
        check(slots(owner).equals(List.of("original", "unrelated", "null")) && replacement.getOwner() == null,
                "Undo removes only the added slot, keeping the later null");

        changes.clear();
        changes.start();
        owner.getChildren().remove(0);
        changes.stop();
        var removal = changes.all().get(0);
        owner.getChildren().add(null);
        check(!removal.isUndoable(), "Removing a slot still requires the recorded post-removal count for undo");
        removal.undo();
        check(slots(owner).equals(List.of("unrelated", "null", "null")) && original.getOwner() == null,
                "Undo skips a stale removal without changing ownership");
    }

    private static void verifyBuilderTransfers() {
        var source = builderSource();
        var own = source.getChildren();
        var repeated = own.get(0);
        var events = new ArrayList<String>();
        watchBuilderList(source, "source", events);
        var staged = LegacyContainer.newBuilder().applyFrom(source);
        check(events.isEmpty() && repeated.getOwner() == source, "Import stages existing child identities");
        staged.applyTo(source);
        check(source.getChildren() == own && slots(source).equals(List.of("null", "a", "null", "b", "a")),
                "Appending to the source preserves each null and retains repeated owning identities");
        check(own.get(1) == repeated && own.get(4) == repeated && repeated.getOwner() == source,
                "Both retained slots refer to the original child");
        check(events.equals(List.of("source:[]:[0]")), "Same-source append edits: " + events);

        events.clear();
        source = builderSource();
        own = source.getChildren();
        repeated = own.get(0);
        watchBuilderList(source, "source", events);
        LegacyContainer.newBuilder().appendCollections(false).applyFrom(source).applyTo(source);
        check(source.getChildren() == own && slots(source).equals(List.of("null", "b", "a")),
                "Same-source replacement clears the live list and ordinary add coalesces a repeated input");
        check(own.get(2) == repeated && repeated.getOwner() == source, "The original child is reattached");
        check(events.equals(List.of("source:[0, 1, 2, 3]:[]", "source:[]:[0, 1, 2]")),
                "Same-source replacement edits: " + events);

        events.clear();
        source = builderSource();
        own = source.getChildren();
        repeated = own.get(0);
        var middle = own.get(2);
        var old = child("old");
        var target = LegacyContainer.newBuilder().withName("target").withChildren(old).build();
        var targetList = target.getChildren();
        watchBuilderList(source, "source", events);
        watchBuilderList(target, "target", events);
        LegacyContainer.newBuilder().appendCollections(false).applyFrom(source).applyTo(target);
        check(target.getChildren() == targetList && slots(source).equals(List.of("null"))
                        && slots(target).equals(List.of("null", "b", "a")),
                "Replacement transfers owning edges and leaves the source null in place");
        check(targetList.get(1) == middle && targetList.get(2) == repeated && repeated.getOwner() == target
                        && middle.getOwner() == target && old.getOwner() == null,
                "Replacement transfers both original children and detaches the previous target child");
        check(events.equals(List.of("target:[0]:[]", "source:[2]:[]", "source:[0, 2]:[]", "target:[]:[0, 1, 2]")),
                "Distinct-source replacement edits: " + events);
    }

    private static LegacyContainer builderSource() {
        var source = LegacyContainer.newBuilder().withName("source")
                .withChildren(new LegacyChild[]{child("discarded"), null, child("b"), child("a")}).build();
        source.getChildren().set(0, source.getChildren().get(3));
        return source;
    }

    private static void verifyBuilderCycle(boolean append, boolean introducedByObserver) {
        var root = BuilderCycleNode.newInstance();
        var target = BuilderCycleNode.newBuilder().withCaption("branch").build();
        var leaf = BuilderCycleNode.newInstance();
        if (!introducedByObserver) root.getChildren().add(target);
        var children = target.getChildren();
        children.add(leaf);
        var listEvents = new ArrayList<String>();
        var properties = new ArrayList<String>();
        var list = children.addChangeListener(change -> listEvents.add("children"));
        var changes = target.vmf().changes();
        var local = changes.addPropertyListener("caption", change -> {
            properties.add(change.propertyName());
            if (introducedByObserver) root.getChildren().add(target);
        });
        changes.start();
        try {
            var builder = BuilderCycleNode.newBuilder().withCaption("changed").withChildren(root).appendCollections(append);
            try {
                builder.applyTo(target);
                throw new AssertionError("Builder accepted an attachment to its own ancestor");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().contains("cycle"), "Builder rejected the containment cycle");
            }

            var expected = introducedByObserver ? List.of("caption") : List.of();
            // The observer's successful move may also notify an unnamed inverse in Java.
            var history = changes.all().stream().map(Change::propertyName)
                    .filter(name -> !introducedByObserver || target.vmf().reflect().propertyByName(name).isPresent())
                    .collect(Collectors.toList());
            check(properties.equals(expected) && listEvents.isEmpty() && history.equals(expected),
                    "The failed attachment leaves the owning list's observers and history untouched");
            check(target.getCaption().equals(introducedByObserver ? "changed" : "branch")
                            && target.getChildren() == children && children.size() == 1 && children.get(0) == leaf
                            && root.getChildren().get(0) == target
                            && ((VObjectInternal) target)._vmf_getContainer() == root
                            && ((VObjectInternal) leaf)._vmf_getContainer() == target,
                    "The failed attachment preserves the current topology and earlier applicable edits");
        } finally {
            changes.stop();
            local.unsubscribe();
            list.unsubscribe();
        }
    }

    private static void watchBuilderList(LegacyContainer owner, String scope, List<String> events) {
        owner.getChildren().addChangeListener(edit -> events.add(scope + ":" + Arrays.toString(edit.removed().indices())
                + ":" + Arrays.toString(edit.added().indices())));
    }

    private static LegacyLayout fixtureFor(String profile) {
        var child = LegacyChild.newBuilder().withName("a").build();
        var other = LegacyChild.newBuilder().withName("b").build();
        var source = LegacyContainer.newBuilder().withName("source").build();
        source.getChildren().addAll(Arrays.asList(child, null, other, child));
        check(slots(source).equals(List.of("null", "b", "a")), "A repeated input is coalesced into stable insertion order");
        var target = LegacyContainer.newBuilder().withName("target").build();
        var graph = LegacyLayout.newBuilder().withContainers(source, target).build();
        if (profile.equals("repeat")) source.getChildren().set(0, child);
        if (profile.equals("refs")) graph.setFeatured(other);
        return graph;
    }

    private static void verifySnapshot(LegacyLayout graph, String profile) {
        var source = graph.getContainers().get(0);
        check(slots(source).equals(List.of(profile.equals("repeat") ? "a" : "null", "b", "a")),
                "Order, null slots, and all by-value occurrences survive");
        var restored = source.getChildren();
        check(restored.get(0) != restored.get(2) && restored.get(1).getOwner() == source
                        && restored.get(2).getOwner() == source,
                "A repeated owning value is restored as separate by-value children");
        check(profile.equals("refs") ? graph.getFeatured() == restored.get(1) : graph.getFeatured() == null,
                "A unique owned target can be referenced by identity");
    }

    private static List<String> slots(LegacyContainer container) {
        return container.getChildren().stream().map(value -> value == null ? "null" : value.getName())
                .collect(Collectors.toList());
    }

    static ObjectMapper mapper(VMFDocumentFormat format, boolean references) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
