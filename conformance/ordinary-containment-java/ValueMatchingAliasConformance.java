package eu.mihosoft.vmf.conformance.ordinarycontainment;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.runtime.core.VObject;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class ValueMatchingAliasConformance {
    private static final String MODEL = "ordinary-list-alias";
    private static final String CHECKPOINT = "before-matching";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return MODEL; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            return object instanceof ValueMatchedCard ? ((ValueMatchedCard) object).getId() : "list";
        }
        @Override public String type(VObject object) { return object.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("No creations"); }
    };

    private ValueMatchingAliasConformance() { }

    static void verify(boolean write, Path directory) throws IOException {
        if (write) {
            verifyInstanceAliasLookup();
            for (boolean transaction : new boolean[]{false, true}) {
                verifyBulkUndo(false, false, transaction);
                verifyBulkUndo(true, false, transaction);
                verifyBulkUndo(true, true, transaction);
            }
            verifyBulkObserverFailures();
            verifyNullListApply(true);
            verifyNullListApply(false);
            verifyStructuralOwningReplay();
        }
        var baseline = checkpoint();
        var recorded = write ? record(baseline.clone()) : null;
        var json = new ObjectMapper();
        var expected = json.readTree(VMFJournalCodec.write(expected()));
        for (var format : VMFDocumentFormat.values()) {
            var extension = format.name().toLowerCase(Locale.ROOT);
            var prefix = (write ? "java" : "csharp") + "-value-match-";
            var snapshot = directory.resolve(prefix + "checkpoint." + extension);
            var edits = directory.resolve(prefix + "journal." + extension);
            var mapper = OrdinaryContainmentConformance.mapper(format, true);
            if (write) {
                Files.writeString(snapshot, mapper.writeValueAsString(baseline));
                Files.writeString(edits, VMFJournalCodec.write(recorded, format));
            }
            var loaded = VMFJournalCodec.read(Files.readString(edits), format);
            check(loaded.wireVersion() == 1 && json.readTree(VMFJournalCodec.write(loaded)).equals(expected),
                    "Both removals record the first matching slot and its original identity");
            var fresh = mapper.readValue(snapshot.toFile(), ValueMatchedList.class);
            var replayed = JournalReplayer.replay(fresh, BINDING, CHECKPOINT, loaded);
            verifyFinal(replayed, replayed.getChildren().get(0));
            check(replayed.getChildren().get(0) != fresh.getChildren().get(1)
                    && fresh.getChildren().size() == 2 && fresh.getReferences().get(0) == fresh.getChildren().get(0),
                    "Replay uses independent identities and preserves the source graph");
        }
    }

    private static void verifyInstanceAliasLookup() {
        var archive = ArchiveNode.newInstance();
        var node = ArchiveNode.newInstance();
        var alias = node.asReadOnly();
        check(alias.equals(alias) && !alias.equals(node) && !node.equals(alias)
                        && alias.hashCode() == System.identityHashCode(alias), "Instance projections have their own public identity");
        var trail = archive.getTrail();
        trail.addAll(Arrays.asList(node, null, node));
        var projected = archive.asReadOnly().getTrail();
        check(projected.get(0) == alias && projected.contains(alias) && !projected.contains(node)
                        && projected.lastIndexOf(alias) == 2, "A projected list sees the same held instance view");
        check(trail.containsAll(Arrays.asList(alias, null)) && trail.indexOf(alias) == 0 && trail.lastIndexOf(alias) == 2
                        && trail.asUnmodifiable().contains(alias), "Ordinary reference lookup resolves the alias and preserves null slots");
        check(!trail.removeAll(Collections.singletonList(alias)) && trail.remove(alias) && trail.subList(1, 2).remove(alias)
                        && trail.size() == 1 && trail.get(0) == null,
                "Single-member list edits resolve aliases without changing bulk membership");

        var parent = LegacyContainer.newInstance();
        var child = LegacyChild.newInstance();
        var childAlias = child.asReadOnly();
        parent.getChildren().add(child);
        check(parent.getChildren().contains(childAlias) && parent.asReadOnly().getChildren().contains(childAlias)
                        && parent.getChildren().subList(0, 1).remove(childAlias) && child.getOwner() == null,
                "Ordinary owning ranges resolve the alias and detach the actual child");

        var values = CollectionProfile.newInstance().getMessages();
        values.addAll(Arrays.asList("value", null));
        check(values.contains(new String("value")) && values.subList(0, 1).remove(new String("value"))
                        && values.size() == 1 && values.get(0) == null,
                "Ordinary external-value lists retain value matching and null slots");
    }

    private static void verifyBulkUndo(boolean owning, boolean duplicate, boolean transaction) {
        var source = bulkCheckpoint();
        var first = source.getChildren().get(0);
        var spare = source.getChildren().get(1);
        var second = source.getChildren().get(2);
        var list = owning ? source.getChildren() : source.getReferences();
        if (owning && duplicate) {
            list.add(null);
            list.set(4, first);
        }
        var view = owning ? source.asReadOnly().getChildren() : source.asReadOnly().getReferences();
        var original = new ArrayList<>(list);
        var removed = new ArrayList<String>();
        var restored = new ArrayList<String>();
        var events = list.addChangeListener(change -> {
            if (change.wasRemoved()) removed.add(Arrays.toString(change.removed().indices()));
            if (change.wasAdded()) restored.add(Arrays.toString(change.added().indices()));
        });
        var readOnlyEvents = new ArrayList<String>();
        var watchingView = view.addChangeListener(change -> {
            if (change.wasRemoved()) {
                check(change.source() == view, "Read-only bulk listener retains its target");
                check(change.removed().elements().get(0) == first.asReadOnly(),
                        "Read-only bulk event contains the removed projected identity");
                readOnlyEvents.add(Arrays.toString(change.removed().indices()));
            }
        });
        try {
            var changes = source.vmf().changes();
            changes.start();
            check(list.removeAll(Arrays.asList(second.asReadOnly(), null)), "Bulk filter removes equal values and null");
            changes.stop();
            String selected = !owning || duplicate ? "[0, 2, 3, 4]" : "[0, 2, 3]";
            check(removed.equals(List.of(selected)) && removed.equals(readOnlyEvents),
                    "Bulk filter publishes one sparse removal on mutable and read-only lists");
            check(list.size() == 1 && list.get(0) == spare, "Unselected identity survives");
            check((first.getOwner() == source) == !owning && (second.getOwner() == source) == !owning,
                    "Only the owning list clears inverse links");
            if (transaction) changes.transactions().get(0).undo();
            else changes.all().get(0).undo();
            var expected = owning && duplicate ? List.of("[0, 2, 3, 4]")
                    : List.of("[0]", !owning ? "[2, 3, 4]" : "[2, 3]");
            check(restored.equals(expected), "Undo insertion events use runs unless an owning alias must be restored: " + restored);
            check(list.size() == original.size(), "Undo restores the original count");
            for (int i = 0; i < original.size(); i++) {
                check(list.get(i) == original.get(i), "Undo restores the exact original slot at " + i);
            }
            check(first.getOwner() == source && second.getOwner() == source, "Undo preserves or restores both links");
        } finally {
            watchingView.unsubscribe();
            events.unsubscribe();
        }
    }

    private static void verifyBulkObserverFailures() {
        var source = bulkCheckpoint();
        var children = source.getChildren();
        var first = children.get(0);
        var spare = children.get(1);
        var second = children.get(2);
        var adopted = ValueMatchedList.newInstance();
        var inserted = ValueMatchedCard.newBuilder().withId("inserted").withCaption("inserted").build();
        var trace = new ArrayList<String>();
        var firstFailure = new IllegalStateException("first inverse");
        var secondFailure = new IllegalStateException("second inverse");
        var listFailure = new IllegalStateException("list observer");
        var firstObserver = first.vmf().changes().addListener(change -> {
            if (!"owner".equals(change.propertyName()) || first.getOwner() != null) return;
            check(children.size() == 1 && children.get(0) == spare && second.getOwner() == source,
                    "Selected slots are gone before inverse listeners, with the later inverse still pending");
            trace.add("first inverse");
            try {
                children.removeAll(List.of(spare));
                throw new AssertionError("Same-list reentry during inverse maintenance must fail");
            } catch (IllegalArgumentException expected) {
                trace.add("guarded");
            }
            adopted.getChildren().add(first);
            trace.add("adopted");
            throw firstFailure;
        }, false);
        var secondObserver = second.vmf().changes().addListener(change -> {
            if (!"owner".equals(change.propertyName())) return;
            check(second.getOwner() == null, "Later inverse still completes");
            trace.add("second inverse");
            throw secondFailure;
        }, false);
        var listObserver = children.addChangeListener(change -> {
            if (change.wasRemoved()) {
                trace.add("first removal");
                children.add(inserted);
                throw listFailure;
            }
            trace.add("first insertion");
        });
        var laterObserver = children.addChangeListener(change -> {
            if (change.wasRemoved()) {
                check(Arrays.equals(change.removed().indices(), new int[]{0, 2, 3})
                                && change.removed().elements().get(0) == first
                                && change.removed().elements().get(1) == second
                                && change.removed().elements().get(2) == null,
                        "Nested edit leaves the outer sparse payload intact");
                check(children.size() == 2 && children.get(1) == inserted,
                        "A later outer listener sees the nested insertion");
                trace.add("later removal");
            } else {
                trace.add("later insertion");
            }
        });
        try {
            try {
                children.removeAll(Arrays.asList(second.asReadOnly(), null));
                throw new AssertionError("Listener failures must surface after the owning edit");
            } catch (IllegalStateException failure) {
                check(failure == firstFailure
                                && Arrays.equals(failure.getSuppressed(), new Throwable[]{secondFailure, listFailure}),
                        "First listener failure retains later failures");
            }
            check(trace.equals(List.of("first inverse", "guarded", "adopted", "second inverse",
                    "first removal", "first insertion", "later insertion", "later removal")),
                    "Owning edit delivers every inverse and list event: " + trace);
            check(first.getOwner() == adopted && adopted.getChildren().get(0) == first && second.getOwner() == null
                            && inserted.getOwner() == source && children.get(0) == spare,
                    "Failures preserve the committed removal, adoption, and nested insertion");
        } finally {
            laterObserver.unsubscribe();
            listObserver.unsubscribe();
            secondObserver.unsubscribe();
            firstObserver.unsubscribe();
        }
    }

    private static void verifyNullListApply(boolean owning) {
        var first = ValueMatchedCard.newInstance();
        var kept = ValueMatchedCard.newInstance();
        var last = ValueMatchedCard.newInstance();
        var sourceMiddle = ValueMatchedCard.newInstance();
        var source = ValueMatchedList.newInstance();
        var target = ValueMatchedList.newInstance();
        var sourceList = owning ? source.getChildren() : source.getReferences();
        var targetList = owning ? target.getChildren() : target.getReferences();
        sourceList.addAll(Arrays.asList(null, sourceMiddle, null));
        targetList.addAll(List.of(first, kept, last));
        var changes = source.vmf().changes();
        changes.start();
        check(sourceList.removeAll(Arrays.asList((ValueMatchedCard) null)), "Remove the source null slots");
        changes.stop();
        var removed = new ArrayList<ValueMatchedCard>();
        var listener = targetList.addChangeListener(change -> {
            check(Arrays.equals(change.removed().indices(), new int[]{0, 2}), "The target receives the sparse positions");
            removed.addAll(change.removed().elements());
        });
        try {
            changes.all().get(0).apply(target);
            check(removed.size() == 2 && removed.get(0) == first && removed.get(1) == last,
                    "A sparse removal reports the receiving owner's actual values");
            check(sourceList.size() == 1 && sourceList.get(0) == sourceMiddle && targetList.get(0) == kept,
                    "The receiving edit leaves the original list unchanged");
            check(first.getOwner() == null && last.getOwner() == null && (kept.getOwner() == target) == owning,
                    "The receiving list updates only its own inverse ownership");
        } finally {
            listener.unsubscribe();
        }

        changes.start();
        sourceList.add(0, null);
        changes.stop();
        changes.all().get(0).apply(target);
        check(targetList.size() == 2 && targetList.get(0) == null, "Null insertion can apply to the target");

        changes.start();
        sourceList.set(0, null);
        changes.stop();
        targetList.set(0, first);
        changes.all().get(0).apply(target);
        check(targetList.get(0) == null && first.getOwner() == null, "Null replacement can detach a target value");

        changes.start();
        sourceList.set(0, first);
        changes.stop();
        try {
            changes.all().get(0).apply(target);
            throw new AssertionError("Replay onto a different owner must reject an actual model payload");
        } catch (IllegalArgumentException expected) {
            check(targetList.get(0) == null, "Rejected replay leaves receiver state intact");
        }
    }

    private static void verifyStructuralOwningReplay() {
        var source = ValueMatchedList.newInstance();
        source.getChildren().add(null);
        var target = LegacyContainer.newInstance();
        var child = LegacyChild.newInstance();
        target.getChildren().add(child);

        var changes = source.vmf().changes();
        changes.start();
        source.getChildren().remove(0);
        changes.stop();
        var removed = new ArrayList<LegacyChild>();
        var listener = target.getChildren().addChangeListener(change -> removed.addAll(change.removed().elements()));
        try {
            changes.all().get(0).apply(target);
        } finally {
            listener.unsubscribe();
        }
        check(target.getChildren().isEmpty() && child.getOwner() == null && removed.size() == 1 && removed.get(0) == child,
                "A structural owning target clears its local child's inverse and reports that child");
    }

    private static ValueMatchedList bulkCheckpoint() {
        var first = ValueMatchedCard.newBuilder().withId("first").withCaption("same").build();
        var second = ValueMatchedCard.newBuilder().withId("second").withCaption("same").build();
        var spare = ValueMatchedCard.newBuilder().withId("spare").withCaption("spare").build();
        return ValueMatchedList.newBuilder().withChildren(Arrays.asList(first, spare, second, null))
                .withReferences(Arrays.asList(first, spare, second, null, first)).build();
    }

    private static ChangeJournal record(ValueMatchedList graph) {
        var first = graph.getChildren().get(0);
        var second = graph.getChildren().get(1);
        var held = second.asReadOnly();
        check(first != second && first.equals(second), "Different identifiers are intentionally excluded from value equality");
        var notifications = new ArrayList<String>();
        var references = graph.getReferences().addChangeListener(change -> {
            check(change.removed().indices()[0] == 0 && change.removed().elements().get(0) == first,
                    "Reference notifications use the selected slot and native object");
            notifications.add("references");
        });
        var children = graph.getChildren().addChangeListener(change -> {
            check(change.removed().indices()[0] == 0 && change.removed().elements().get(0) == first,
                    "Owning notifications use the selected slot and native object");
            notifications.add("children");
        });
        try (var recorder = JournalRecorder.start(graph, BINDING, CHECKPOINT, 20)) {
            check(graph.getReferences().contains(held) && graph.getReferences().indexOf(held) == 0
                    && graph.getReferences().remove(held) && first.getOwner() == graph, "Remove the first value-equal reference");
            check(recorder.publishFrame().orElseThrow().sequence() == 21, "Reference frame publication");
            check(graph.getChildren().contains(held) && graph.getChildren().indexOf(held) == 0
                    && graph.getChildren().remove(held) && first.getOwner() == null, "Remove and detach the same owning slot");
            check(notifications.equals(List.of("references", "children")), "Each removal publishes once in edit order");
            verifyFinal(graph, second);
            return recorder.finish();
        } finally {
            references.unsubscribe();
            children.unsubscribe();
        }
    }

    private static ChangeJournal expected() {
        return new ChangeJournal(MODEL, 1, "list", CHECKPOINT, 20,
                List.of(new Frame(21, List.of(removeFirst("references"))), new Frame(22, List.of(removeFirst("children")))));
    }

    private static Operation removeFirst(String property) {
        return new Operation(Kind.LIST_REMOVE, "list", property, List.of(0),
                List.of(new Value(ValueKind.REFERENCE, "first")), List.of(), List.of());
    }

    private static ValueMatchedList checkpoint() {
        var first = ValueMatchedCard.newBuilder().withId("first").withCaption("same").build();
        var second = ValueMatchedCard.newBuilder().withId("second").withCaption("same").build();
        return ValueMatchedList.newBuilder().withChildren(first, second)
                .withReferences(Arrays.asList(first, null, second)).build();
    }

    private static void verifyFinal(ValueMatchedList graph, ValueMatchedCard second) {
        check(graph.getChildren().size() == 1 && graph.getChildren().get(0) == second && "second".equals(second.getId())
                && second.getOwner() == graph && graph.getReferences().size() == 2 && graph.getReferences().get(0) == null
                && graph.getReferences().get(1) == second, "The later identity and its remaining references are preserved");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
