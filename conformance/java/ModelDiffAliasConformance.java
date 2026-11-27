package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
final class ModelDiffAliasConformance {
    private ModelDiffAliasConformance() { }

    static void verify() {
        redirectIntoNestedContainment();
        preserveSharedUnownedTargets();
        shareAReferenceToTheCopyOfANewDescendant();
        swapOwningFields();
        redirectOutboundLinksFromTheNewCopy();
    }

    private static void redirectIntoNestedContainment() {
        var root = node("root");
        var left = node("left");
        var holder = node("holder");
        var right = node("right");
        root.getChildren().add(left);
        root.setInset(holder);
        holder.setInset(right);
        root.setAnchor(left);
        root.getTrail().addAll(Arrays.asList(left, null));
        root.getPeers().add(left);
        var desired = root.clone();
        var next = desired.getInset().getInset();
        desired.setAnchor(next);
        desired.getTrail().set(0, next);
        desired.getTrail().add(next);
        desired.getPeers().clear();
        desired.getPeers().add(next);

        var edits = ModelDiff.diff(root, desired);
        check(edits.stream().map(Change::propertyName).collect(Collectors.toSet())
                        .equals(java.util.Set.of("anchor", "trail", "peers")),
                "Only the redirected properties change");
        check(root.getAnchor() == left && root.getTrail().get(0) == left && left.getName().equals("left"),
                "Diff planning leaves the source unchanged");

        ModelDiff.apply(root, edits);
        check(root.getChildren().get(0) == left && root.getInset() == holder && holder.getInset() == right
                        && left.getName().equals("left") && right.getName().equals("right"),
                "Redirecting links keeps the live owned nodes in their slots");
        check(root.getAnchor() == right && root.getTrail().get(0) == right && root.getTrail().get(1) == null
                        && root.getTrail().get(2) == right && root.getPeers().contains(right),
                "All ordinary references point at the nested counterpart");
        undo(edits);
        check(root.getAnchor() == left && root.getTrail().equals(Arrays.asList(left, null))
                        && root.getPeers().contains(left),
                "Undo restores the exact original aliases");
    }

    private static void preserveSharedUnownedTargets() {
        var shared = node("shared");
        var source = node("root");
        var desired = node("root");
        source.getTrail().addAll(Arrays.asList(shared, null, shared));
        source.getPeers().add(shared);
        desired.getTrail().addAll(Arrays.asList(shared, null, shared));
        desired.getPeers().add(shared);
        desired.setAnchor(shared);

        var edits = ModelDiff.diff(source, desired);
        check(edits.size() == 1 && edits.get(0).propertyName().equals("anchor"),
                "Exact unowned identities stay unchanged across independent roots");
        ModelDiff.apply(source, edits);
        check(source.getAnchor() == shared && source.getTrail().get(0) == shared && source.getPeers().contains(shared),
                "The new reference uses the shared unowned instance");
        undo(edits);
    }

    private static void shareAReferenceToTheCopyOfANewDescendant() {
        var source = node("root");
        var desired = source.clone();
        var branch = node("new branch");
        var child = node("new leaf");
        branch.setInset(child);
        desired.getChildren().add(branch);
        desired.setAnchor(child);
        desired.getTrail().addAll(Arrays.asList(child, null, child));

        var edits = ModelDiff.diff(source, desired);
        check(branch.getParent() == desired && child.getInsetOf() == branch && source.getChildren().isEmpty(),
                "Planning new nodes does not move the desired subtree");
        ModelDiff.apply(source, edits);
        var copiedBranch = source.getChildren().get(0);
        var copiedChild = copiedBranch.getInset();
        check(copiedBranch != branch && copiedChild != child && copiedChild.getInsetOf() == copiedBranch
                        && source.getAnchor() == copiedChild && source.getTrail().get(0) == copiedChild
                        && source.getTrail().get(1) == null && source.getTrail().get(2) == copiedChild,
                "Every link uses the same independently copied node");
        undo(edits);
        check(source.getChildren().isEmpty() && source.getAnchor() == null && source.getTrail().isEmpty(),
                "Undo removes the added branch and its references");
    }

    private static void swapOwningFields() {
        var source = node("root");
        var left = node("left");
        var right = node("right");
        source.getChildren().add(left);
        source.setInset(right);
        source.setAnchor(left);
        var desired = source.clone();
        desired.getChildren().add(desired.getInset());
        desired.setInset(desired.getChildren().get(0));

        var edits = ModelDiff.diff(source, desired);
        check(edits.stream().noneMatch(change -> change.propertyName().equals("anchor")),
                "Moving an existing child does not replace a reference to it");
        check(left.getParent() == source && right.getInsetOf() == source, "Diff planning does not move source children");
        ModelDiff.apply(source, edits);
        check(source.getInset() == left && source.getChildren().get(0) == right && source.getAnchor() == left
                        && left.getInsetOf() == source && right.getParent() == source,
                "Swapping owning fields keeps both original identities and opposites");
        undo(edits);
        check(source.getChildren().get(0) == left && source.getInset() == right && source.getAnchor() == left
                        && left.getParent() == source && right.getInsetOf() == source,
                "Reverse undo restores both original ownership slots");
    }

    private static void redirectOutboundLinksFromTheNewCopy() {
        var source = node("root");
        var retained = node("retained");
        source.getChildren().add(retained);
        var desired = source.clone();
        var reference = desired.getChildren().get(0);
        var branch = node("new branch");
        var leaf = node("new leaf");
        branch.setInset(leaf);
        branch.setAnchor(reference);
        leaf.getTrail().addAll(Arrays.asList(reference, null, reference));
        desired.setInset(branch);
        desired.setAnchor(branch);

        var attachmentNotifications = new java.util.ArrayList<Boolean>();
        var listener = source.vmf().changes().addLocalListener(event -> {
            if (!event.propertyName().equals("inset") || source.getInset() == null) return;
            var attached = source.getInset();
            attachmentNotifications.add(attached.getAnchor() == retained && attached.getInset().getTrail().get(0) == retained);
        });
        try {
            var edits = ModelDiff.diff(source, desired);
            var newInset = (ModelDiff.PropChange) edits.stream().filter(edit -> edit.propertyName().equals("inset"))
                    .findFirst().orElseThrow();
            var copy = (ArchiveNode) newInset.newValue();
            var detached = copy.getAnchor();
            check(detached != retained && detached != reference && copy.getInset().getTrail().get(0) == detached
                            && source.getInset() == null && source.getChildren().get(0) == retained && attachmentNotifications.isEmpty(),
                    "Diff prepares only an isolated copy and leaves the live graph untouched");

            ModelDiff.apply(source, edits);
            var trail = copy.getInset().getTrail();
            check(source.getInset() == copy && source.getAnchor() == copy && copy.getAnchor() == retained
                            && trail.get(0) == retained && trail.get(1) == null && trail.get(2) == retained
                            && attachmentNotifications.equals(List.of(true)),
                    "The new branch and its descendants use the retained sibling when observers see attachment");
            check(branch.getAnchor() == reference && leaf.getTrail().get(0) == reference
                            && retained.getParent() == source, "Neither input graph supplies a moved child");

            undo(edits);
            check(source.getInset() == null && source.getAnchor() == null && source.getChildren().get(0) == retained
                            && copy.getAnchor() == detached && trail.get(0) == detached && trail.get(2) == detached,
                    "Undo restores both the source and the private copy's links");
            ModelDiff.apply(source, edits);
            check(source.getInset() == copy && copy.getAnchor() == retained && trail.get(0) == retained,
                    "Reapplying the reversible patch reuses the canonical target");
        } finally {
            listener.unsubscribe();
        }
    }

    private static void undo(List<Change> edits) {
        for (int index = edits.size() - 1; index >= 0; index--) {
            check(edits.get(index).isUndoable(), "Each staged change can be undone in reverse order");
            edits.get(index).undo();
        }
    }

    private static ArchiveNode node(String name) { return ArchiveNode.newBuilder().withName(name).build(); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
