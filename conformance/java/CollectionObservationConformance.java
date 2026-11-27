package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vcollections.VMapChangeEvent;
import eu.mihosoft.vcollections.VSetChangeEvent;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Change.ChangeType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class CollectionObservationConformance {
    private CollectionObservationConformance() {}

    public static void main(String[] args) {
        checkPositionalScalarList();
        checkScalarListFilters();
        checkBulkMapAndDeferredHistory();
        checkTypedScalarSet();
        checkReferenceCollections();
        ReferenceListObservationConformance.verify();
        ProjectedListObservationConformance.verify();
        System.out.println("Java verified managed collection deltas, reentrant history, and native undo and replay.");
    }

    private static void checkPositionalScalarList() {
        var profile = CollectionProfile.newInstance();
        var before = List.of("plan", "measure", "record");
        var after = List.of("record", "plan", "measure");
        var phases = profile.getPhases();
        phases.addAll(before);
        var changes = profile.vmf().changes();
        var observed = new ArrayList<Change>();
        var subscription = changes.addRecursiveListener(observed::add);
        changes.start();
        try {
            check(!phases.add("plan"), "ordered scalar set rejects redundant add without an event");
            phases.move(2, 0);
        } finally {
            subscription.unsubscribe();
            changes.stop();
        }
        check(observed.size() == 1 && changes.all().size() == 1, "one positional change for an ordered scalar move");
        var change = observed.get(0);
        var delta = change.listChange().orElseThrow();
        check(change.object() == profile && change.propertyName().equals("phases") && change.getType() == ChangeType.LIST
                && sameIdentity(delta.source(), phases) && delta.wasSet(), "ordered scalar move uses a list replacement event");
        check(Arrays.equals(delta.removed().indices(), new int[]{0, 1, 2})
                && Arrays.equals(delta.added().indices(), new int[]{0, 1, 2})
                && delta.removed().elements().equals(before) && delta.added().elements().equals(after),
                "move delta contains previous and next values at the affected positions");
        check(change.isUndoable(), "move can be undone at its recorded positions");
        change.undo();
        check(phases.equals(before), "undo restores the prior order");
        change.apply(profile);
        var other = CollectionProfile.newInstance();
        other.getPhases().addAll(before);
        change.apply(other);
        check(phases.equals(after) && other.getPhases().equals(after), "scalar list change replays on compatible owners");
        check(delta.removed().elements().equals(before), "move payload survives later mutations");
    }

    private static void checkScalarListFilters() {
        var candidates = Arrays.asList(-0.0, null, Double.NaN);
        var baseline = Arrays.asList(+0.0, -0.0, null, 4.0, -0.0,
                Double.longBitsToDouble(0x7ff8000000000042L), Double.longBitsToDouble(0xfff8000000000017L), 7.0);
        for (boolean retain : new boolean[]{false, true}) {
            var source = CollectionProfile.newInstance();
            var readings = source.getReadings();
            readings.addAll(baseline);
            var events = new ArrayList<String>();
            var listener = readings.addChangeListener(change -> events.add(change.wasRemoved()
                    ? "-" + Arrays.toString(change.removed().indices())
                    : "+" + Arrays.toString(change.added().indices())));
            try {
                var changes = source.vmf().changes();
                changes.start();
                check(retain ? readings.retainAll(candidates) : readings.removeAll(candidates), "Scalar filter changes the list");
                changes.stop();
                int[] positions = retain ? new int[]{0, 3, 7} : new int[]{1, 2, 4, 5, 6};
                var removed = Arrays.stream(positions).mapToObj(baseline::get).collect(Collectors.toList());
                var survivors = retain ? Arrays.asList(-0.0, null, -0.0, Double.NaN, Double.NaN)
                        : List.of(+0.0, 4.0, 7.0);
                var sparse = "-" + Arrays.toString(positions);
                check(events.equals(List.of(sparse)) && readings.equals(survivors),
                        "One sparse filter edit distinguishes signed zeros and unifies NaNs");
                check(changes.all().size() == 1, "One modeled edit for the filter");
                var removal = changes.all().get(0);
                var delta = removal.listChange().orElseThrow();
                check(Arrays.equals(delta.removed().indices(), positions) && delta.removed().elements().equals(removed),
                        "The sparse edit retains the original slots and values");
                check(removal.isUndoable(), "The filter removal is undoable");
                removal.undo();
                var expectedEvents = retain ? List.of(sparse, "+[0]", "+[3]", "+[7]") : List.of(sparse, "+[1, 2]", "+[4, 5, 6]");
                check(events.equals(expectedEvents), "Undo emits contiguous runs: " + events);
                for (int i = 0; i < baseline.size(); i++) {
                    var value = baseline.get(i);
                    check(value == null ? readings.get(i) == null
                                    : readings.get(i) != null && Double.doubleToRawLongBits(value) == Double.doubleToRawLongBits(readings.get(i)),
                            "Undo preserves raw value bits at " + i);
                }
                events.clear();
                removal.apply(source);
                check(events.equals(List.of(sparse)) && readings.equals(survivors), "Apply to the source repeats the sparse removal");

                var other = CollectionProfile.newInstance();
                var otherBaseline = List.of(91.0, 92.0, 93.0, 94.0, 95.0, 96.0, 97.0, 98.0);
                other.getReadings().addAll(otherBaseline);
                var actualRemoved = new ArrayList<Double>();
                var observingOther = other.getReadings().addChangeListener(change -> {
                    check(Arrays.equals(change.removed().indices(), positions), "Receiver uses the recorded positions");
                    actualRemoved.addAll(change.removed().elements());
                });
                try {
                    removal.apply(other);
                    check(actualRemoved.equals(Arrays.stream(positions).mapToObj(otherBaseline::get).collect(Collectors.toList())),
                            "Apply delivers the values from the receiving owner");
                    check(other.getReadings().equals(retain ? List.of(92.0, 93.0, 95.0, 96.0, 97.0)
                                    : List.of(91.0, 94.0, 98.0)),
                            "The compatible owner loses precisely the recorded slots");
                } finally {
                    observingOther.unsubscribe();
                }
            } finally {
                listener.unsubscribe();
            }
        }

        var profile = CollectionProfile.newInstance();
        profile.getCounts().addAll(List.of(1, 2, 1));
        var changes = profile.vmf().changes();
        changes.start();
        check(profile.getCounts().removeAll(List.of(1)), "Bounded nonunique scalar lists support the same filter");
        changes.stop();
        var removal = changes.all().get(0);
        check(Arrays.equals(removal.listChange().orElseThrow().removed().indices(), new int[]{0, 2}),
                "The bounded list records both slots");
        removal.undo();
        check(profile.getCounts().equals(List.of(1, 2, 1)), "Undo restores bounded size and order");
    }

    private static void checkBulkMapAndDeferredHistory() {
        var profile = CollectionProfile.newInstance();
        var limits = profile.getLimits();
        limits.put("updated", null);
        limits.put("kept", 8L);
        var updates = new LinkedHashMap<String, Long>();
        updates.put("updated", 10L);
        updates.put("added", null);
        updates.put("kept", 8L);
        var changes = profile.vmf().changes();
        var history = changes.all();
        var observed = new ArrayList<Change>();
        var order = new ArrayList<String>();
        var first = changes.addRecursiveListener(change -> {
            observed.add(change);
            order.add("first " + change.propertyName());
            if (change.getType() == ChangeType.MAP) {
                check(limits.get("updated") == 10L && limits.containsKey("added"), "bulk map commit precedes live delivery");
                profile.getMessages().add("nested");
            }
            check(history.isEmpty(), "map and nested list records wait for the initiating live callbacks");
        });
        var second = changes.addRecursiveListener(change -> order.add("second " + change.propertyName()));
        changes.start();
        try {
            limits.putAll(updates);
        } finally {
            first.unsubscribe();
            second.unsubscribe();
            changes.stop();
        }
        check(order.equals(List.of("first limits", "first messages", "second messages", "second limits")),
                "bulk map dispatch completes a nested list edit synchronously");
        check(properties(history).equals(List.of("limits", "messages")), "map enters history before its nested list edit");
        var change = observed.get(0);
        var edit = change.mapChange().orElseThrow();
        var before = edit.removed().entries();
        var after = edit.added().entries();
        check(change.object() == profile && sameIdentity(edit.source(), limits) && before.size() == 2
                && before.containsKey("updated") && before.get("updated") == null && before.get("kept").equals(8L),
                "map delta retains the previous present-null mapping and includes unchanged submitted entries: " + before);
        check(after.size() == 3 && after.get("updated").equals(10L) && after.containsKey("added") && after.get("added") == null
                && after.get("kept").equals(8L), "map delta distinguishes insertion with null from absence: " + after);

        var other = CollectionProfile.newInstance();
        other.getLimits().put("updated", null);
        other.getLimits().put("kept", 8L);
        check(change.isUndoable(), "map with present-null entries supports undo");
        change.undo();
        check(limits.equals(other.getLimits()), "undo restores presence independently of value");
        change.apply(profile);
        change.apply(other);
        check(limits.equals(other.getLimits()) && limits.size() == 3 && limits.containsKey("added"),
                "map change replays its bulk delta on compatible owners");

        var assignments = new ArrayList<Change>();
        var observingAssignments = changes.addLocalListener(assignments::add);
        try {
            limits.putAll(Map.of("kept", 8L));
            limits.putAll(Map.of());
        } finally {
            observingAssignments.unsubscribe();
        }
        check(assignments.size() == 1, "unchanged bulk assignment emits, empty bulk assignment stays silent");
        var unchanged = assignments.get(0).mapChange().orElseThrow();
        check(unchanged.removed().entries().equals(Map.of("kept", 8L)) && unchanged.added().entries().equals(Map.of("kept", 8L)),
                "equal-value bulk assignment carries the same entry on both sides");
        checkDirectScalarMapObservers();
    }

    private static void checkTypedScalarSet() {
        var profile = CollectionProfile.newInstance();
        var levels = profile.getLevels();
        levels.addAll(Set.of(+0.0, Double.NaN));
        var observed = new ArrayList<Change>();
        var subscription = profile.vmf().changes().addLocalListener(observed::add);
        try {
            check(!levels.add(Double.longBitsToDouble(0x7ff8000000000001L)), "NaN payloads use the same set member");
            levels.replaceContents(Set.of(-0.0, Double.NaN));
        } finally {
            subscription.unsubscribe();
        }
        check(observed.size() == 1, "one event for effective set membership edit");
        var change = observed.get(0);
        var edit = change.setChange().orElseThrow();
        check(change.object() == profile && change.getType() == ChangeType.SET && sameIdentity(edit.source(), levels)
                && edit.removed().equals(Set.of(+0.0)) && edit.added().equals(Set.of(-0.0)),
                "set delta distinguishes zero signs while ignoring retained NaN");
        check(change.isUndoable(), "set delta can restore its previous members");
        change.undo();
        check(levels.equals(Set.of(+0.0, Double.NaN)), "set undo uses typed equality");
        var other = CollectionProfile.newInstance();
        other.getLevels().addAll(levels);
        change.apply(other);
        check(other.getLevels().equals(Set.of(-0.0, Double.NaN)), "set delta applies to a compatible owner");
        checkDirectScalarSetObservers();
    }

    private static void checkDirectScalarMapObservers() {
        var profile = CollectionProfile.newInstance();
        var limits = profile.getLimits();
        var view = profile.asReadOnly().getLimits();
        var mutable = new ArrayList<VMapChangeEvent<String, Long>>();
        var projected = new ArrayList<VMapChangeEvent<String, Long>>();
        var mutableListener = limits.addChangeListener(mutable::add);
        var viewListener = view.addChangeListener(projected::add);
        limits.put("existing", null);
        var updates = new LinkedHashMap<String, Long>();
        updates.put("existing", 7L);
        updates.put("new", null);
        limits.putAll(updates);
        check(mutable.size() == 2 && projected.size() == 2 && mutable.get(0).source() == limits
                        && projected.get(0).source() == view, "The map routes report their exact direct source identities");
        var batch = projected.get(1);
        check(batch.removed().entries().containsKey("existing") && batch.removed().entries().get("existing") == null
                        && !batch.removed().entries().containsKey("new") && batch.added().entries().get("existing") == 7L
                        && batch.added().entries().containsKey("new") && batch.added().entries().get("new") == null,
                "A bulk event distinguishes old and new present-null entries from missing keys");
        viewListener.unsubscribe();
        limits.remove("new");
        mutableListener.unsubscribe();
        limits.clear();
        check(mutable.size() == 3 && projected.size() == 2 && batch.added().entries().size() == 2,
                "Map disposal stops future callbacks and leaves captured entries intact");

        var events = new ArrayList<VMapChangeEvent<Double, Character>>();
        var typed = profile.getMarkers().addChangeListener(events::add);
        var markers = new LinkedHashMap<Double, Character>();
        markers.put(0.0, 'p');
        markers.put(-0.0, 'n');
        markers.put(Double.NaN, null);
        profile.getMarkers().putAll(markers);
        typed.unsubscribe();
        var entries = events.get(0).added().entries();
        check(entries.size() == 3 && entries.get(0.0) == 'p' && entries.get(-0.0) == 'n'
                        && entries.containsKey(Double.longBitsToDouble(0x7ff8000000000001L))
                        && entries.get(Double.NaN) == null,
                "Typed map snapshots keep different signed zeros and recognise alternate NaN keys");
    }

    private static void checkDirectScalarSetObservers() {
        var profile = CollectionProfile.newInstance();
        var levels = profile.getLevels();
        var view = profile.asReadOnly().getLevels();
        var mutable = new ArrayList<VSetChangeEvent<Double>>();
        var projected = new ArrayList<VSetChangeEvent<Double>>();
        var viewListener = view.addChangeListener(projected::add);
        var sourceListener = levels.addChangeListener(mutable::add);
        levels.addAll(List.of(0.0, -0.0, Double.NaN, Double.longBitsToDouble(0x7ff8000000000001L)));
        check(mutable.size() == 1 && projected.size() == 1, "Direct set observers receive one bulk event");
        var change = projected.get(0);
        check(mutable.get(0).source() == levels && change.source() == view, "Direct set sources preserve the getter identities");
        check(change.removed().isEmpty() && change.added().size() == 3 && change.added().containsAll(Set.of(0.0, -0.0, Double.NaN)),
                "The set event distinguishes signed zeros and deduplicates NaN variants");
        check(!levels.add(Double.NaN), "A duplicate set addition does not emit an edit");
        viewListener.unsubscribe();
        levels.remove(0.0);
        sourceListener.unsubscribe();
        levels.clear();
        check(mutable.size() == 2 && projected.size() == 1 && change.added().size() == 3,
                "Disposal stops future set events and leaves snapshots stable");
        check(mutable.get(1).removed().equals(Set.of(0.0)), "The removal identifies the former scalar member");

        var order = new ArrayList<String>();
        var expected = new IllegalStateException("read-only set listener");
        var first = view.addChangeListener(event -> {
            order.add("failing view");
            throw expected;
        });
        var next = view.addChangeListener(event -> order.add("view"));
        var source = levels.addChangeListener(event -> order.add("mutable"));
        var changes = profile.vmf().changes();
        var owner = changes.addPropertyListener("levels", event -> order.add("owner"));
        changes.start();
        try {
            levels.add(1.0);
            throw new AssertionError("The failing set listener should be reported");
        } catch (IllegalStateException failure) {
            check(failure == expected, "The original observer exception is reported");
        } finally {
            first.unsubscribe();
            next.unsubscribe();
            source.unsubscribe();
            owner.unsubscribe();
            changes.stop();
        }
        check(order.equals(List.of("mutable", "owner", "failing view", "view")) && levels.contains(1.0)
                        && changes.all().size() == 1,
                "The source and later projected observers keep the committed edit and native history: " + order);
    }

    private static void checkReferenceCollections() {
        var owner = ArchiveNode.newInstance();
        var repeated = ArchiveNode.newInstance();
        var changes = owner.vmf().changes();
        var history = changes.all();
        var observed = new ArrayList<Change>();
        var subscription = changes.addRecursiveListener(change -> {
            observed.add(change);
            if (change.propertyName().equals("trail")) {
                check(history.isEmpty(), "reference list is pending at start of its live callback");
                check(owner.getFeatured().add(repeated), "nested ordered reference set add");
                check(properties(history).equals(List.of("featured")), "nested ref set records before its initiating list edit");
            }
        });
        changes.start();
        try {
            owner.getTrail().addAll(Arrays.asList(repeated, null, repeated));
        } finally {
            subscription.unsubscribe();
            changes.stop();
        }
        // The triggering edit is recorded before the edits its listeners made (portable contract H4).
        check(properties(observed).equals(List.of("trail", "featured"))
                && properties(history).equals(List.of("trail", "featured")), "reference collection dispatch and history order");
        var listChange = observed.get(0);
        var list = listChange.listChange().orElseThrow();
        var values = list.added().elements();
        check(listChange.getType() == ChangeType.LIST && list.wasAdded()
                && Arrays.equals(list.added().indices(), new int[]{0, 1, 2}) && values.size() == 3
                && values.get(0) == repeated && values.get(1) == null && values.get(2) == repeated,
                "reference sequence payload preserves repeated identity, null, and exact positions");
        rejectModelReplay(() -> listChange.apply(ArchiveNode.newInstance()));
        check(listChange.isUndoable(), "reference list addition can be undone on the original owner");
        listChange.undo();
        check(owner.getTrail().isEmpty(), "reference list undo removes all added slots");
        listChange.apply(owner);
        check(owner.getTrail().get(0) == repeated && owner.getTrail().get(1) == null && owner.getTrail().get(2) == repeated,
                "reference list replay preserves input identities on its original owner");

        var ordered = observed.get(1);
        var orderedEdit = ordered.referenceSetChange().orElseThrow();
        check(ordered.getType() == ChangeType.REFERENCE_SET && ordered.listChange().isEmpty()
                && orderedEdit.source() == owner.getFeatured() && orderedEdit.indices().equals(List.of(0))
                && orderedEdit.added().size() == 1 && orderedEdit.added().get(0) == repeated,
                "ordered identity set exposes its own type and positions");
        rejectModelReplay(() -> ordered.apply(ArchiveNode.newInstance()));
        check(ordered.isUndoable(), "ordered membership can be undone at the recorded position");
        ordered.undo();
        check(owner.getFeatured().isEmpty(), "ordered set undo removes its added member");
        ordered.apply(owner);
        check(owner.getFeatured().get(0) == repeated, "ordered set replay uses the recorded position and identity");

        var selection = ValueSelection.newInstance();
        var a = ValueLabel.newBuilder().withText("label").build();
        var b = ValueLabel.newBuilder().withText("label").build();
        check(a != b && a.equals(b), "unordered reference set fixtures share content");
        var selected = selection.getSelected();
        var memberships = new ArrayList<Change>();
        var observingSelection = selection.vmf().changes().addLocalListener(memberships::add);
        try {
            check(selected.addAll(List.of(a, a, b)), "bulk identity set add");
            check(!selected.add(a), "same identity is a no-op");
            check(selected.remove(a) && selected.contains(b), "equal-content member retains separate identity");
        } finally {
            observingSelection.unsubscribe();
        }
        check(memberships.size() == 2, "one event per effective identity membership edit");
        var added = memberships.get(0).referenceSetChange().orElseThrow();
        check(added.source() == selected && added.indices().isEmpty() && added.added().size() == 2
                && added.added().stream().anyMatch(member -> member == a) && added.added().stream().anyMatch(member -> member == b),
                "unordered identity set reports distinct identities without positional indices");
        var removal = memberships.get(1);
        check(removal.referenceSetChange().orElseThrow().removed().get(0) == a && removal.isUndoable(),
                "identity removal carries the exact removed instance");
        rejectModelReplay(() -> removal.apply(ValueSelection.newInstance()));
        removal.undo();
        check(selected.size() == 2 && selected.contains(a) && selected.contains(b), "identity set undo restores both equal values");
        removal.apply(selection);
        check(selected.size() == 1 && !selected.contains(a) && selected.contains(b), "identity set replay removes only its original member");
    }

    private static List<String> properties(List<Change> changes) {
        return changes.stream().map(Change::propertyName).collect(Collectors.toList());
    }

    private static boolean sameIdentity(Object left, Object right) {
        return left == right;
    }

    private static void rejectModelReplay(Runnable apply) {
        try {
            apply.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("reference delta applied to a foreign owner");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
