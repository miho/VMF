package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.collectionredeclarations.CollectionBasis;
import eu.mihosoft.vmf.conformance.collectionredeclarations.CollectionItem;
import eu.mihosoft.vmf.conformance.collectionredeclarations.CollectionVariant;
import eu.mihosoft.vmf.conformance.collectiondefaults.FrozenSamples;
import eu.mihosoft.vmf.conformance.collectiondefaults.DefaultCollections;
import eu.mihosoft.vmf.conformance.collectiondefaults.ManagedSamples;
import eu.mihosoft.vmf.conformance.equality.EqualityContainer;
import eu.mihosoft.vmf.conformance.equality.EqualityNode;
import eu.mihosoft.vmf.conformance.external.ExternalValueEntry;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
final class ModelDiffUndoConformance {
    private ModelDiffUndoConformance() { }

    public static void main(String[] args) { verify(); }

    static void verify() {
        var ordinary = EqualityNode.newBuilder().withName("root").build();
        var desiredOrdinary = ordinary.clone();
        desiredOrdinary.setNext(child());
        verifyStaleLink(ordinary, desiredOrdinary, ordinary::getNext, ordinary::setNext, "next");

        var owning = EqualityContainer.newInstance();
        var desiredOwning = EqualityContainer.newBuilder().withChild(child()).build();
        verifyStaleLink(owning, desiredOwning, owning::getChild, owning::setChild, "child");

        var scalar = EqualityNode.newBuilder().withName("before").build();
        var scalarDesired = EqualityNode.newBuilder().withName("after").build();
        var scalarChanges = ModelDiff.diff(scalar, scalarDesired);
        ModelDiff.apply(scalar, scalarChanges);
        String assigned = scalar.getName();
        scalar.setName("other");
        scalar.setName(new String(assigned));
        check(scalar.getName() != assigned && scalarChanges.get(0).isUndoable(), "Scalar undo still uses value equality");
        scalarChanges.get(0).undo();
        check(scalar.getName().equals("before"), "Scalar undo restores the recorded value");

        String id = "8df6dcb5-21de-42b4-868e-e4e4032eddc5";
        var external = ExternalValueEntry.newInstance();
        var externalDesired = ExternalValueEntry.newBuilder().withSourceId(UUID.fromString(id)).build();
        var externalChanges = ModelDiff.diff(external, externalDesired);
        ModelDiff.apply(external, externalChanges);
        UUID original = external.getSourceId();
        external.setSourceId(null);
        external.setSourceId(UUID.fromString(id));
        check(external.getSourceId() != original && externalChanges.get(0).isUndoable(),
                "External UUID undo still uses value equality");
        externalChanges.get(0).undo();
        check(external.getSourceId() == null, "External undo restores the recorded value");

        verifyCrossTypeCollections();
        verifyCrossTypeRelationships();
        verifyManagedMapOrder();
        verifyMapOrderOnIntroducedChild();
    }

    private static void verifyMapOrderOnIntroducedChild() {
        var child = ManagedSamples.newInstance();
        child.getOptions().clear();
        child.getOptions().put("known", 11);
        child.getOptions().put("present", null);
        var source = DefaultCollections.newInstance();
        var desired = DefaultCollections.newBuilder().withMutable(child).build();

        ModelDiff.apply(source, ModelDiff.diff(source, desired));

        var imported = source.getMutable();
        check(imported != child && desired.getMutable() == child
                        && List.copyOf(imported.getOptions().keySet()).equals(List.of("known", "present"))
                        && imported.getOptions().containsKey("present") && imported.getOptions().get("present") == null,
                "An introduced child keeps its map order even when its mappings match the constructor defaults");
    }

    private static void verifyManagedMapOrder() {
        var source = CollectionProfile.newInstance();
        source.getOptions().put("first", null);
        source.getOptions().put("second", true);
        source.getMarkers().put(+0.0, 'a');
        source.getMarkers().put(-0.0, null);
        var desired = CollectionProfile.newInstance();
        desired.getOptions().put("second", false);
        desired.getOptions().put("first", null);
        desired.getMarkers().put(-0.0, 'b');
        desired.getMarkers().put(+0.0, 'a');
        var options = source.getOptions();
        var readOnlyOptions = source.asReadOnly().getOptions();
        var patch = ModelDiff.diff(source, desired);
        desired.getOptions().clear();
        desired.getMarkers().clear();

        var order = new ArrayList<List<String>>();
        var listener = source.vmf().changes().addPropertyListener("options", change ->
                order.add(List.copyOf(options.keySet())));
        try {
            ModelDiff.apply(source, patch);
            check(options == source.getOptions() && List.copyOf(readOnlyOptions.keySet()).equals(List.of("second", "first"))
                            && Boolean.FALSE.equals(options.get("second")) && options.containsKey("first") && options.get("first") == null,
                    "Apply preserves the stored map, captured key order, and the present-null entry");
            check(List.copyOf(source.getMarkers().keySet()).equals(List.of(-0.0, +0.0))
                            && Character.valueOf('b').equals(source.getMarkers().get(-0.0)),
                    "Apply reorders distinct signed-zero keys in a typed map");
            check(order.equals(List.of(List.of("second"), List.of("second", "first"))), "Map replacement reports the reinserted key");
            order.clear();

            for (int i = patch.size() - 1; i >= 0; i--) patch.get(i).undo();
            check(List.copyOf(options.keySet()).equals(List.of("first", "second")) && Boolean.TRUE.equals(options.get("second"))
                            && List.copyOf(source.getMarkers().keySet()).equals(List.of(+0.0, -0.0)) && source.getMarkers().get(-0.0) == null,
                    "Undo restores the previous key order and values");
            check(order.equals(List.of(List.of("first"), List.of("first", "second"))), "Undo reports the reverse reinsertion");

            var reordered = CollectionProfile.newInstance();
            reordered.getOptions().put("second", true);
            reordered.getOptions().put("first", null);
            reordered.getMarkers().put(+0.0, 'a');
            reordered.getMarkers().put(-0.0, null);
            check(ModelDiff.diff(source, reordered).isEmpty(), "Diff compares map mappings regardless of order");
            var template = source.asReadOnly();
            var overlay = reordered.asReadOnly();
            var merged = ModelDiff.merge(template, overlay);
            check(merged != template && List.copyOf(merged.getOptions().keySet()).equals(List.of("second", "first"))
                            && merged.getOptions().containsKey("first") && merged.getOptions().get("first") == null
                            && List.copyOf(template.getOptions().keySet()).equals(List.of("first", "second"))
                            && List.copyOf(overlay.getOptions().keySet()).equals(List.of("second", "first")),
                    "Merge keeps the captured overlay order and both input maps");

            options.put("second", false);
            ModelDiff.apply(source, patch);
            check(List.copyOf(options.keySet()).equals(List.of("second", "first")), "An applied edit restores its exact map order");
        } finally {
            listener.unsubscribe();
        }
    }

    private static void verifyCrossTypeCollections() {
        var source = ManagedSamples.newInstance();
        var numbers = source.getNumbers();
        var options = source.getOptions();
        numbers.clear();
        numbers.addAll(List.of(3, 3));
        options.clear();
        options.put("before", null);
        var desired = FrozenSamples.newBuilder().withNumbers(8).withLabels("frozen").withCodes(9L)
                .withOptions(Map.of("after", 11)).build();

        var changes = ModelDiff.diff(source, desired);
        check(changes.stream().map(Change::propertyName).sorted().collect(Collectors.toList())
                .equals(List.of("codes", "labels", "numbers", "options")), "Only the differing shared collections are patched");
        ModelDiff.apply(source, changes);
        check(source.getNumbers() == numbers && numbers.equals(List.of(8))
                        && source.getOptions() == options && options.equals(Map.of("after", 11)),
                "An immutable desired model replaces contents within the source's live storage");
        for (int i = changes.size() - 1; i >= 0; i--) changes.get(i).undo();
        check(numbers.equals(List.of(3, 3)) && options.containsKey("before") && options.get("before") == null
                        && !options.containsKey("after"), "Collection undo restores duplicates and present-null");
    }

    private static void verifyCrossTypeRelationships() {
        for (int shape = 0; shape < 3; shape++) {
            boolean owning = shape != 1;
            boolean reciprocal = shape != 0;
            var source = CollectionBasis.newInstance();
            var desired = CollectionVariant.newInstance();
            var oldFirst = item("a");
            var oldSecond = item("b");
            var desiredFirst = item("b");
            var desiredSecond = item("c");
            if (owning) {
                source.getOwned().addAll(List.of(oldFirst, oldSecond));
                desired.getOwned().addAll(List.of(desiredFirst, desiredSecond));
            }
            if (reciprocal) {
                source.getFeatured().add(oldFirst);
                desired.getFeatured().add(desiredSecond);
            }
            source.getTrail().add(oldSecond);
            desired.getTrail().add(desiredFirst);

            var changes = ModelDiff.diff(source, desired.asReadOnly());
            check(!owning || oldFirst.getBasis() == source && desiredFirst.getBasis() == desired,
                    "Capture leaves both ownership graphs unchanged");
            check(!reciprocal || oldFirst.getSelectedBy() == source && desiredSecond.getSelectedBy() == desired,
                    "Capture leaves both reciprocal graphs unchanged");
            ModelDiff.apply(source, changes);
            var imported = source.getTrail().get(0);
            check(imported != desiredFirst, "The imported reference is independent");
            if (owning) {
                check(source.getOwned().get(0) == imported && imported != oldSecond && imported.getBasis() == source
                                && oldFirst.getBasis() == null && oldSecond.getBasis() == null && desiredFirst.getBasis() == desired,
                        "Across different roots, ownership imports a fresh key-equal child and reconnects its aliases");
            }
            if (reciprocal) {
                var selected = source.getFeatured().get(0);
                check(selected != desiredSecond && selected.getSelectedBy() == source
                                && oldFirst.getSelectedBy() == null && desiredSecond.getSelectedBy() == desired,
                        "The reciprocal edit sets the source inverse and leaves the desired inverse intact");
                check(!owning || selected == source.getOwned().get(1), "Owning and reciprocal fields reuse one imported identity");
            }

            for (int i = changes.size() - 1; i >= 0; i--) changes.get(i).undo();
            check(source.getTrail().get(0) == oldSecond, "Undo restores the original ordinary reference");
            check(!owning || source.getOwned().get(0) == oldFirst && source.getOwned().get(1) == oldSecond
                            && oldFirst.getBasis() == source && desiredSecond.getBasis() == desired,
                    "Undo restores the original owned members and inverses");
            check(!reciprocal || source.getFeatured().get(0) == oldFirst && oldFirst.getSelectedBy() == source
                            && desiredSecond.getSelectedBy() == desired,
                    "Undo restores the original reciprocal members and inverses");
        }
    }

    private static CollectionItem item(String name) {
        return CollectionItem.newBuilder().withName(name).build();
    }

    private static void verifyStaleLink(VObject source, VObject desired, Supplier<EqualityNode> current,
                                        Consumer<EqualityNode> replace, String propertyName) {
        var changes = ModelDiff.diff(source, desired);
        check(changes.size() == 1 && changes.get(0).propertyName().equals(propertyName), "One singular link edit");
        var change = changes.get(0);
        ModelDiff.apply(source, changes);
        var applied = current.get();
        check(change.isUndoable(), "The captured reference is undoable");
        check(ModelDiff.diff(source, source).isEmpty() && ModelDiff.diff(source, source.clone()).isEmpty(),
                "Identical graphs and independent equivalent copies have no diff");
        var noOp = new ModelDiff.PropChange(source, propertyName, applied, applied);
        check(noOp.isUndoable(), "A no-op on the captured reference stays undoable");
        noOp.undo();
        check(current.get() == applied, "A no-op does not replace its reference");

        var replacement = applied.clone();
        check(replacement != applied && replacement.equals(applied), "Distinct mutable instances have equal modeled values");
        replace.accept(replacement);
        check(current.get() == replacement && !change.isUndoable() && !noOp.isUndoable(),
                "An equal-content replacement is stale for singular model undo");
        change.undo();
        check(current.get() == replacement, "A stale undo leaves the replacement installed");

        replace.accept(applied);
        check(change.isUndoable(), "Restoring the exact applied identity makes the undo available");
        change.undo();
        check(current.get() == null, "Undo restores the original empty link");
    }

    private static EqualityNode child() { return EqualityNode.newBuilder().withName("equal").build(); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
