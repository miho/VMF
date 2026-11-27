package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.equality.EqualityContainer;
import eu.mihosoft.vmf.conformance.equality.EqualityNode;
import eu.mihosoft.vmf.conformance.ordinarycontainment.ReadingRoom;
import eu.mihosoft.vmf.conformance.ordinarycontainment.Shelf;
import eu.mihosoft.vmf.conformance.ordinarycontainment.Volume;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

@SuppressWarnings("deprecation")
final class ModelDiffOrderedSetsConformance {
    private ModelDiffOrderedSetsConformance() { }

    public static void main(String[] args) {
        verifyUnorderedCounterparts();
        if (args.length == 0) verifyIgnoredField();
    }

    static void verify() {
        verifyUnorderedCounterparts();
        verifyIgnoredField();
    }

    private static void verifyIgnoredField() {
        var source = EqualityContainer.newInstance();
        var child = EqualityNode.newBuilder().withName("child").withNote("before").build();
        source.getChildren().add(child);
        var desired = source.clone();
        desired.getChildren().get(0).setNote("after");

        check(source.vmf().content().equals(desired), "The field stays excluded from content equality");
        var changes = ModelDiff.diff(source, desired);
        check(changes.size() == 1, "The ordered set child has one real Note edit");
        var change = changes.get(0);
        check(change.object() == child && change.propertyName().equals("note"),
                "The patch records the field edit on the matched child");
        check(source.getChildren().get(0) == child && child.getNote().equals("before"), "Planning leaves the child untouched");
        ModelDiff.apply(source, changes);
        check(source.getChildren().get(0) == child && child.getContainer() == source && child.getNote().equals("after"),
                "Applying keeps the same child and updates its Note");
        check(change.isUndoable(), "The new value allows undo");
        change.undo();
        check(source.getChildren().get(0) == child && child.getContainer() == source && child.getNote().equals("before"),
                "Undo keeps the same child and restores its Note");
    }

    private static void verifyUnorderedCounterparts() {
        for (int choice = 0; choice < 2; choice++) {
            var source = ReadingRoom.newInstance();
            var shelf = Shelf.newBuilder().withName("shelf").build();
            var first = Volume.newBuilder().withName("same").build();
            var second = Volume.newBuilder().withName("same").build();
            shelf.getVolumes().add(first);
            shelf.getVolumes().add(second);
            source.getShelves().add(shelf);
            var desired = source.clone();
            var desiredMembers = desired.getShelves().get(0).getVolumes().iterator();
            var expected = choice == 0 ? first : second;
            desired.setFeatured(desiredMembers.next());
            if (choice == 1) desired.setFeatured(desiredMembers.next());
            check(first != second && first.vmf().content().equals(second), "Fixture has two equivalent identities");

            var edits = ModelDiff.diff(source, desired);
            check(edits.size() == 1 && edits.get(0).propertyName().equals("featured"),
                    "Unordered containment selects one counterpart without replacing set membership");
            ModelDiff.apply(source, edits);
            check(source.getFeatured() == expected && shelf.getVolumes().contains(first) && shelf.getVolumes().contains(second)
                            && first.getShelf() == shelf && second.getShelf() == shelf,
                    "The unordered set resolves equivalent members in existing iteration order");
            edits.get(0).undo();
            check(source.getFeatured() == null, "Unordered matching remains reversible");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
