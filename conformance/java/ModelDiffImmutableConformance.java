package eu.mihosoft.vmf.conformance;

import eu.mihosoft.vmf.conformance.values.ValueLabel;
import eu.mihosoft.vmf.conformance.values.ValueSelection;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

@SuppressWarnings("deprecation")
final class ModelDiffImmutableConformance {
    private ModelDiffImmutableConformance() { }

    static void verify() {
        var previous = label("previous");
        var selected = label("selected");
        var source = ValueSelection.newBuilder().withCurrent(previous).withSecond(previous).build();
        source.getSelected().add(previous);
        var desired = ValueSelection.newBuilder().withCurrent(selected).withSecond(selected).build();
        desired.getSelected().add(selected);

        var changes = ModelDiff.diff(source, desired);
        check(changes.size() == 3 && changes.stream().allMatch(change -> change.object() == source),
                "Immutable references are replaced on the mutable owner");
        ModelDiff.apply(source, changes);
        check(source.getCurrent() == selected && source.getSecond() == selected
                        && source.getSelected().contains(selected) && !source.getSelected().contains(previous),
                "Replacement preserves the alias across singular and set references");
        for (int i = changes.size() - 1; i >= 0; i--) {
            check(changes.get(i).isUndoable(), "An unchanged replacement is undoable");
            changes.get(i).undo();
        }
        check(source.getCurrent() == previous && source.getSecond() == previous && source.getSelected().contains(previous),
                "Undo restores the original alias");

        var sameContents = label("previous");
        check(sameContents != previous && sameContents.equals(previous), "Fixture values have equal contents and distinct identities");
        desired.setCurrent(sameContents);
        desired.setSecond(previous);
        desired.getSelected().clear();
        desired.getSelected().add(previous);
        var converging = ModelDiff.diff(source, desired);
        check(converging.size() == 1 && converging.get(0).propertyName().equals("current"),
                "Equal immutable values can still change identity");
        ModelDiff.apply(source, converging);
        check(source.getCurrent() == sameContents && source.getSecond() == previous, "Equal content does not hide an alias change");

        Change change = converging.get(0);
        source.setCurrent(label("previous"));
        check(!change.isUndoable(), "A distinct equal replacement makes captured undo stale");
    }

    private static ValueLabel label(String text) { return ValueLabel.newBuilder().withText(text).build(); }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
