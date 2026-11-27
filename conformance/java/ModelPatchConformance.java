package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyChild;
import eu.mihosoft.vmf.conformance.ordinarycontainment.LegacyContainer;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.ArchiveRoot;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.KeyedInventory;
import eu.mihosoft.vmf.conformance.values.KeyedItem;
import eu.mihosoft.vmf.conformance.values.ReadOnlyArchiveNode;
import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.example.workshops.Station;
import eu.mihosoft.vmf.example.workshops.Workshop;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
final class ModelPatchConformance {
    private ModelPatchConformance() {}

    public static void main(String[] args) {
        ModelDiffImmutableConformance.verify();
        checkCollectionsAndOverwrite();
        ModelDiffOrderedSetsConformance.verify();
        checkSourceBindingAndReachability();
        ModelDiffUndoConformance.verify();
        checkOrdinaryContainmentPatchAfterObserverFailure();
        checkOwningAndNonOwningAliases();
        ModelDiffAliasConformance.verify();
        checkNewBranchAtAttachment();
        checkKeyedLookupAfterApply();
        checkMergeDefaultsAndCollections();
        checkMergeInstanceDefaults();
        checkMergeReadOnlyTemplate();
        checkMergeOverlayRootReferences();
        checkMergeDetachedReferences();
        checkMergeMissingInputs();
        System.out.println("Java verified source-bound diff, collection snapshots, containment aliases, and merge defaults.");
    }

    private static void checkOrdinaryContainmentPatchAfterObserverFailure() {
        var source = LegacyContainer.newInstance();
        var retained = LegacyChild.newBuilder().withName("retained").build();
        var departing = LegacyChild.newBuilder().withName("departing").build();
        source.getChildren().addAll(List.of(retained, departing));
        var desired = source.clone();
        var arriving = LegacyChild.newBuilder().withName("arriving").build();
        desired.getChildren().set(1, arriving);

        var patch = ModelDiff.diff(source, desired);
        check(patch.size() == 1 && patch.get(0).propertyName().equals("children"), "One ordinary containment edit");
        var failure = new IllegalStateException("observer");
        var trace = new ArrayList<Integer>();
        var subscription = source.vmf().changes().addLocalListener(change -> {
            if (!change.propertyName().equals("children")) return;
            trace.add(source.getChildren().size());
            check(source.getChildren().get(0) == retained && retained.getOwner() == source
                            && source.getChildren().get(1).getOwner() == source,
                    "The observer sees final slots and the retained inverse");
            throw failure;
        });
        try {
            try {
                ModelDiff.apply(source, patch);
                throw new AssertionError("The patch should report the observer failure");
            } catch (IllegalStateException expected) {
                check(expected == failure, "The patch reports the original failure");
            }
        } finally {
            subscription.unsubscribe();
        }
        var inserted = source.getChildren().get(1);
        check(trace.equals(List.of(2)) && inserted != arriving && inserted.getName().equals("arriving")
                        && departing.getOwner() == null && arriving.getOwner() == desired,
                "The range commit survives the failure without linking the desired graph");
        check(patch.get(0).isUndoable(), "The committed replacement remains undoable");
        patch.get(0).undo();
        check(source.getChildren().get(0) == retained && source.getChildren().get(1) == departing
                        && departing.getOwner() == source && inserted.getOwner() == null,
                "Undo restores the old membership and inverses");
    }

    private static void checkCollectionsAndOverwrite() {
        var source = CollectionProfile.newInstance();
        var map = source.getOptions();
        map.put("present", null);
        source.getLevels().add(1.0);
        source.getMessages().addAll(Arrays.asList("old", null));
        var desired = source.clone();
        desired.getOptions().clear();
        desired.getOptions().put("new", true);
        desired.getLevels().clear();
        desired.getLevels().add(2.0);
        desired.getMessages().add("new");
        var changes = ModelDiff.diff(source, desired);

        desired.getOptions().put("new", false);
        desired.getMessages().clear();
        map.put("stale", false);
        var events = new ArrayList<String>();
        var listener = source.vmf().changes().addLocalListener(change ->
                events.add(change.getType().name() + ":" + change.propertyName()));
        try {
            ModelDiff.apply(source, changes);
            check(map == source.getOptions() && map.equals(Map.of("new", true)), "owned map snapshots overwrite stale values");
            check(source.getLevels().equals(Set.of(2.0)) && source.getMessages().equals(Arrays.asList("old", null, "new")),
                    "set and positional sequences preserve the captured values");
            check(events.contains("MAP:options") && events.contains("SET:levels") && events.contains("LIST:messages"),
                    "apply uses live managed collection mutators");

            undo(changes);
            check(map.size() == 1 && map.containsKey("present") && map.get("present") == null,
                    "undo restores a present-null map value");
            check(source.getLevels().equals(Set.of(1.0)) && source.getMessages().equals(Arrays.asList("old", null)),
                    "undo restores captured collection values");
        } finally {
            listener.unsubscribe();
        }
    }

    private static void checkSourceBindingAndReachability() {
        var child = node("child");
        var source = node("source");
        source.setInset(child);
        var desired = source.clone();
        desired.setName("updated");
        desired.getInset().setName("updated child");
        var changes = ModelDiff.diff(source, desired);
        reject(() -> ModelDiff.apply(source.clone(), changes));
        source.setInset(null);
        var other = node("other");
        other.setInset(child);
        reject(() -> ModelDiff.apply(source, changes));
        check(source.getName().equals("source") && child.getName().equals("child") && other.getInset() == child,
                "the source and every affected object are checked before the first edit");
    }

    private static void checkOwningAndNonOwningAliases() {
        var original = station("A");
        var source = Workshop.newBuilder().withStations(original).build();
        var desired = source.clone();
        var added = station("B");
        desired.getStations().add(added);
        desired.getFavorites().add(added);

        var changes = ModelDiff.diff(source, desired);
        check(changes.stream().map(Change::propertyName).collect(Collectors.toList()).equals(List.of("favorites", "stations")),
                "deferred reference-set comparison");
        ModelDiff.apply(source, changes);
        var copied = source.getStations().get(1);
        check(original == source.getStations().get(0) && copied != added && source.getFavorites().contains(copied),
                "applied reference targets the new contained copy");
        check(added.getWorkshop() == desired && copied.getWorkshop() == source, "opposites stay in their own graphs");
        undo(changes);
        check(source.getStations().get(0) == original && source.getStations().size() == 1 && source.getFavorites().isEmpty(),
                "reverse undo keeps the original child");

        var merged = ModelDiff.merge(Workshop.newInstance(), desired);
        check(merged.getFavorites().contains(merged.getStations().get(1)) && added.getWorkshop() == desired,
                "merge preserves contained aliases without moving the desired child");
    }

    private static void checkKeyedLookupAfterApply() {
        var source = KeyedInventory.newInstance();
        source.getProducts().add(KeyedItem.newBuilder().withSku("A").withQuantity(1).build());
        var desired = source.clone();
        desired.findProductsBySku("A").orElseThrow().setQuantity(2);
        ModelDiff.apply(source, ModelDiff.diff(source, desired));
        check(source.findProductsBySku("A").orElseThrow().getQuantity() == 2,
                "keyed lookup resolves the changed entry");
    }

    private static void checkNewBranchAtAttachment() {
        var source = ArchiveRoot.newInstance();
        var desired = ArchiveRoot.newInstance();
        var branch = node("branch");
        var inset = node("inset");
        var leaf = node("leaf");
        branch.setInset(inset);
        inset.getChildren().add(leaf);
        branch.setAnchor(leaf);
        leaf.setAnchor(branch);
        desired.setHead(branch);

        var events = new ArrayList<String>();
        var subscription = source.vmf().changes().addRecursiveListener(change -> {
            if (change.object() != source || !change.propertyName().equals("head")) {
                events.add(change.propertyName());
                return;
            }
            var attached = source.getHead();
            if (attached == null) {
                events.add("detach");
                return;
            }
            var nested = attached.getInset();
            check(nested != null && nested.getInsetOf() == attached && nested.getChildren().size() == 1,
                    "The new branch owns its nested objects when the root attachment is published");
            var nestedLeaf = nested.getChildren().get(0);
            check(nestedLeaf.getParent() == nested && attached.getAnchor() == nestedLeaf && nestedLeaf.getAnchor() == attached,
                    "Inverse links and internal aliases are visible to the root observer");
            events.add("attach:" + nestedLeaf.getName());
        });
        try {
            var changes = ModelDiff.diff(source, desired);
            check(source.getHead() == null && events.isEmpty(), "Planning the patch does not edit the source");
            ModelDiff.apply(source, changes);
            var applied = source.getHead();
            var appliedLeaf = applied.getInset().getChildren().get(0);
            check(applied != branch && appliedLeaf != leaf && leaf.getParent() == inset && desired.getHead() == branch,
                    "The patch copies the branch without moving any desired nodes");
            undo(changes);
            check(source.getHead() == null && applied.getInset().getChildren().get(0) == appliedLeaf,
                    "Undo detaches the whole branch with its descendants intact");
            ModelDiff.apply(source, changes);
            check(source.getHead() == applied && source.getHead().getInset().getChildren().get(0) == appliedLeaf,
                    "Reapply attaches the same captured branch");
            appliedLeaf.setName("after attach");
            check(events.equals(List.of("attach:leaf", "detach", "attach:leaf", "name")),
                    "The root observes attachment, detachment and later edits to the attached branch: " + events);
        } finally {
            subscription.unsubscribe();
        }
    }

    private static void checkMergeDefaultsAndCollections() {
        var template = DefaultSettings.newBuilder().withName("template").withLimit(9).withNote("template note").build();
        var overlay = DefaultSettings.newInstance();
        check(ModelDiff.merge(template, overlay).getName().equals("template"), "unset defaults retain the template");
        overlay.setName(null);
        var explicitNull = ModelDiff.merge(template, overlay);
        check(explicitNull.getName() == null && explicitNull.getLimit() == 9 && explicitNull.getNote().equals("template note"),
                "explicit null overrides a non-null default");

        var first = CollectionProfile.newInstance();
        first.getOptions().put("old", null);
        first.getLevels().add(1.0);
        first.getMessages().add("old");
        var second = CollectionProfile.newInstance();
        second.getOptions().put("new", false);
        second.getLevels().add(2.0);
        second.getMessages().add("new");
        var combined = ModelDiff.merge(first, second);
        check(combined.getOptions().equals(Map.of("new", false)) && combined.getLevels().equals(Set.of(2.0))
                && combined.getMessages().equals(List.of("old", "new")), "maps and sets replace while lists append");
        check(ModelDiff.merge(first, CollectionProfile.newInstance()).getOptions().containsKey("old"),
                "an empty override map is unset");
    }

    private static void checkMergeInstanceDefaults() {
        var template = DefaultSettings.newBuilder().withName("template").build();
        var overlay = DefaultSettings.newInstance();
        overlay.vmf().reflect().propertyByName("name").orElseThrow().setDefault("overlay default");
        check(overlay.getName().equals("overlay default") && ModelDiff.merge(template, overlay).getName().equals("template"),
                "An overlay value equal to its instance default does not replace scalar values");
        overlay.setName("edit");
        check(ModelDiff.merge(template, overlay).getName().equals("edit"), "A real overlay edit replaces the scalar");

        var current = CollectionProfile.newInstance();
        current.getOptions().put("kept", null);
        current.getLevels().add(1.0);
        current.getCounts().add(1);

        var collectionOverlay = CollectionProfile.newInstance();
        var properties = collectionOverlay.vmf().reflect();
        properties.propertyByName("options").orElseThrow().setDefault(Map.of("new", true));
        properties.propertyByName("levels").orElseThrow().setDefault(Set.of(2.0));
        properties.propertyByName("counts").orElseThrow().setDefault(List.of(7));
        var result = ModelDiff.merge(current, collectionOverlay);
        check(result.getOptions().size() == 1 && result.getOptions().containsKey("kept")
                        && result.getOptions().get("kept") == null && result.getLevels().equals(Set.of(1.0))
                        && result.getCounts().equals(List.of(1, 7)) && collectionOverlay.getOptions().get("new"),
                "Instance-default maps and sets are ignored while lists append");

        collectionOverlay.getOptions().put("new", false);
        collectionOverlay.getLevels().add(3.0);
        result = ModelDiff.merge(current, collectionOverlay);
        check(result.getOptions().equals(Map.of("new", false)) && result.getLevels().equals(Set.of(2.0, 3.0))
                        && result.getCounts().equals(List.of(1, 7)),
                "Changed maps and sets replace from the overlay");
    }

    private static void undo(List<Change> changes) {
        for (int i = changes.size() - 1; i >= 0; i--) {
            check(changes.get(i).isUndoable(), "applied edit can be undone");
            changes.get(i).undo();
        }
    }

    private static void checkMergeReadOnlyTemplate() {
        var template = node("template");
        var originalChild = node("child");
        template.getChildren().add(originalChild);
        template.setAnchor(originalChild);
        originalChild.setAnchor(template);
        var overlay = node("edited");
        var view = template.asReadOnly();

        var merged = ModelDiff.merge(view, overlay.asReadOnly());
        var child = merged.getChildren().get(0);
        check(!(merged instanceof ArchiveNode) && merged != view && merged.getName().equals("edited"),
                "The overlay updates an independent copy and returns its read-only projection");
        check(child != originalChild.asReadOnly() && child == merged.getAnchor() && child.getAnchor() == merged
                        && child.getParent() == merged,
                "The projected result retains copied aliases, cycles, and ownership");
        try {
            merged.vmf().reflect().propertyByName("name").orElseThrow().set("not allowed");
            throw new AssertionError("The merged projection must not be writable");
        } catch (RuntimeException expected) {
            check(merged.getName().equals("edited"), "Rejected writes cannot change the merged projection");
        }
        check(template.getName().equals("template") && template.getChildren().get(0) == originalChild
                        && originalChild.getAnchor() == template && overlay.getName().equals("edited"),
                "Both input graphs remain independent of the merged projection");
    }

    private static void checkMergeOverlayRootReferences() {
        for (boolean projected : List.of(false, true)) {
            var template = node("template");
            var overlay = node("edited");
            overlay.setAnchor(overlay);
            overlay.getTrail().addAll(Arrays.asList(overlay, null, overlay));
            overlay.getPeers().add(overlay);
            var merged = mergeArchive(template, overlay, projected);
            check(merged.getAnchor() == merged && merged.getTrail().get(0) == merged
                            && merged.getTrail().get(1) == null && merged.getTrail().get(2) == merged
                            && merged.getPeers().iterator().next() == merged,
                    "Direct overlay self-references resolve to the merged copy");
            check(template.getAnchor() == null && template.getPeers().isEmpty()
                            && overlay.getAnchor() == overlay && overlay.getTrail().get(2) == overlay
                            && overlay.getPeers().iterator().next() == overlay,
                    "Merging a self-reference leaves both original graphs unchanged");

            var childTemplate = node("template");
            var childOverlay = node("overlay");
            var originalChild = node("owned");
            childOverlay.getChildren().add(originalChild);
            childOverlay.setAnchor(originalChild);
            originalChild.setAnchor(childOverlay);
            originalChild.getTrail().addAll(List.of(originalChild, childOverlay));
            var combined = mergeArchive(childTemplate, childOverlay, projected);
            var child = combined.getChildren().get(0);
            check(child != originalChild.asReadOnly() && child == combined.getAnchor() && child.getParent() == combined
                            && child.getAnchor() == combined && child.getTrail().get(0) == child
                            && child.getTrail().get(1) == combined,
                    "An imported owned child points to the merged root and retains its internal aliases");
            check(childTemplate.getChildren().isEmpty() && childOverlay.getChildren().get(0) == originalChild
                            && originalChild.getParent() == childOverlay && originalChild.getAnchor() == childOverlay,
                    "Copying the ownership cycle leaves both original graphs intact");
        }
    }

    private static ReadOnlyArchiveNode mergeArchive(ArchiveNode template, ArchiveNode overlay, boolean projected) {
        if (projected) return ModelDiff.merge(template.asReadOnly(), overlay.asReadOnly());
        return ModelDiff.merge(template, overlay).asReadOnly();
    }

    private static void checkMergeDetachedReferences() {
        for (boolean projected : List.of(false, true)) {
            var template = node("template");
            var overlay = node("overlay");
            var shared = node("shared");
            overlay.setAnchor(shared);
            overlay.getTrail().addAll(Arrays.asList(shared, null, shared));
            overlay.getPeers().add(shared);
            shared.setAnchor(overlay);

            var merged = mergeArchive(template, overlay, projected);
            var copied = merged.getAnchor();
            check(copied != null && copied != shared.asReadOnly() && copied.getName().equals("shared")
                            && copied.getAnchor() == merged,
                    "The independent detached copy links back to the merged root");
            check(merged.getTrail().size() == 3 && merged.getTrail().get(0) == copied
                            && merged.getTrail().get(1) == null && merged.getTrail().get(2) == copied,
                    "The ordinary reference list retains repeated aliases and null slots");
            check(merged.getPeers().size() == 1 && merged.getPeers().iterator().next() == copied,
                    "Singular, positional and set references share the same copied identity");
            shared.setName("changed after merge");
            check(copied.getName().equals("shared") && shared.getAnchor() == overlay
                            && overlay.getAnchor() == shared && overlay.getPeers().iterator().next() == shared
                            && template.getAnchor() == null && template.getTrail().isEmpty() && template.getPeers().isEmpty(),
                    "Neither input shares mutable state with the merged graph");
        }
    }

    private static void checkMergeMissingInputs() {
        var original = node("root");
        var shared = node("child");
        original.getChildren().add(shared);
        original.setAnchor(shared);
        shared.setAnchor(original);
        for (var copy : List.of(ModelDiff.merge(original, null), ModelDiff.merge(null, original))) {
            var child = copy.getChildren().get(0);
            check(copy != original && child != shared && copy.getAnchor() == child && child.getAnchor() == copy,
                    "A single mutable merge input is copied with its aliases and cycle");
        }
        check(ModelDiff.<ArchiveNode>merge(null, null) == null, "Two missing inputs produce no model");
        var immutable = ValueLabel.newBuilder().withText("shared").build();
        check(ModelDiff.merge(immutable, null) == immutable && ModelDiff.merge(null, immutable) == immutable,
                "A single immutable merge input retains its identity");
    }

    private static ArchiveNode node(String name) { return ArchiveNode.newBuilder().withName(name).build(); }
    private static Station station(String name) { return Station.newBuilder().withName(name).build(); }

    private static void reject(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected source-bound rejection");
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
