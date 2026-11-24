package eu.mihosoft.vmftest.diff;

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;
import eu.mihosoft.vmftest.managedmap.ManagedSettings;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests for the simple diff/merge utility.
 */
public class ModelDiffTest {

    @Test
    public void managedMapDiffOwnsItsSnapshotsForApplyAndUndo() {
        ManagedSettings source = ManagedSettings.newInstance();
        var scores = source.getScores();
        scores.put("missing", null);
        ManagedSettings desired = source.clone();
        desired.getScores().clear();
        desired.getScores().put("rating", 3.0);
        List<Change> diff = ModelDiff.diff(source, desired);
        Assert.assertEquals(1, diff.size());

        desired.getScores().put("rating", 4.0);
        scores.put("later edit", 2.0);
        ModelDiff.apply(source, diff);
        Assert.assertSame(scores, source.getScores());
        Assert.assertEquals(Map.of("rating", 3.0), scores);
        Assert.assertTrue(diff.get(0).isUndoable());

        diff.get(0).undo();
        Assert.assertEquals(1, scores.size());
        Assert.assertTrue(scores.containsKey("missing"));
        Assert.assertNull(scores.get("missing"));
        Assert.assertEquals(Map.of("rating", 4.0), desired.getScores());
    }

    @Test
    public void testSimplePropertyDiff() {
        NodeToDiff node1 = NodeToDiff.newBuilder()
                .withName("Node1")
                .withValue(10)
                .build();

        NodeToDiff node2 = node1.clone();
        node2.setName("Node2");

        List<Change> diff = ModelDiff.diff(node1, node2);
        Assert.assertEquals(1, diff.size());
        ModelDiff.PropChange c = (ModelDiff.PropChange) diff.get(0);
        Assert.assertEquals("name", c.propertyName());
        Assert.assertEquals("Node1", c.oldValue());
        Assert.assertEquals("Node2", c.newValue());

        ModelDiff.apply(node1, diff);
        Assert.assertEquals("Node2", node1.getName());
        Assert.assertEquals(node2.getValue(), node1.getValue());
        Assert.assertEquals(node2.getChildren().size(), node1.getChildren().size());
        Assert.assertEquals(node2.getValues().size(), node1.getValues().size());
    }

    @Test
    public void rejectsDiffOnAnIndependentRoot() {
        NodeToDiff source = NodeToDiff.newBuilder().withName("Original").build();
        NodeToDiff target = source.clone();
        NodeToDiff desired = source.clone();
        desired.setName("Updated");
        List<Change> diff = ModelDiff.diff(source, desired);

        Assert.assertThrows(IllegalArgumentException.class, () -> ModelDiff.apply(target, diff));
        Assert.assertThrows(IllegalArgumentException.class, () -> diff.get(0).apply(target));
        Assert.assertEquals("Original", source.getName());
        Assert.assertEquals("Original", target.getName());
        Assert.assertEquals("Updated", desired.getName());
    }

    @Test
    public void validatesAllChangeRootsBeforeApplyingTheDiff() {
        NodeToDiff source = NodeToDiff.newBuilder().withName("Original").build();
        NodeToDiff desired = source.clone();
        desired.setName("Updated");
        NodeToDiff other = source.clone();
        List<Change> mixed = new ArrayList<>(ModelDiff.diff(source, desired));
        var standalone = new ModelDiff.PropChange(other, "name", "Original", "Other");
        mixed.add(standalone);

        Assert.assertThrows(IllegalArgumentException.class, () -> ModelDiff.apply(source, mixed));
        Assert.assertEquals("Original", source.getName());
        Assert.assertEquals("Original", other.getName());

        ModelDiff.apply(other, List.of(standalone));
        Assert.assertEquals("Other", other.getName());
    }

    @Test
    public void rejectsDiffAfterAnAffectedChildLeavesTheSourceGraph() {
        NodeToDiff child = NodeToDiff.newBuilder().withName("Original child").build();
        NodeToDiff source = NodeToDiff.newBuilder().withName("Original root").withChild(child).build();
        NodeToDiff desired = source.clone();
        desired.setName("Updated root");
        desired.getChild().setName("Updated child");
        List<Change> diff = ModelDiff.diff(source, desired);

        source.setChild(null);
        NodeToDiff other = NodeToDiff.newBuilder().withChild(child).build();
        Assert.assertThrows(IllegalArgumentException.class, () -> ModelDiff.apply(source, diff));
        Assert.assertEquals("Original root", source.getName());
        Assert.assertSame(child, other.getChild());
        Assert.assertEquals("Original child", child.getName());
    }

    @Test
    public void testComplexDiffAndMerge() {
        NodeToDiff node1 = NodeToDiff.newBuilder()
                .withName("Node1")
                .withValue(10)
                .withValues(1, 2, 3)
                .withChild(NodeToDiff.newBuilder()
                        .withName("Child1")
                        .withValue(20)
                        .build())
                .withChildren(
                        NodeToDiff.newBuilder().withName("Child2").withValue(30).build(),
                        NodeToDiff.newBuilder().withName("Child3").withValue(40).build())
                .build();

        NodeToDiff node2 = node1.clone();
        node2.getChild().setValue(25);
        node2.getChildren().add(NodeToDiff.newBuilder().withName("Child4").withValue(50).build());
        node2.getValues().add(4);

        List<Change> diff = ModelDiff.diff(node1, node2);
        Assert.assertEquals(3, diff.size());

        ModelDiff.apply(node1, diff);
        Assert.assertEquals(node2.getChild().getValue(), node1.getChild().getValue());
        Assert.assertEquals(node2.getChildren().size(), node1.getChildren().size());
        Assert.assertEquals(node2.getValues().size(), node1.getValues().size());

        NodeToDiff override = NodeToDiff.newBuilder()
                .withName("Override")
                .withValue(100)
                .withChildren(NodeToDiff.newBuilder().withName("Extra").withValue(60).build())
                .build();

        NodeToDiff merged = ModelDiff.merge(node1, override);
        Assert.assertEquals("Override", merged.getName());
        Assert.assertEquals(100, merged.getValue());
        // template had child with value 25 which should remain
        Assert.assertEquals(25, merged.getChild().getValue());
        // children list = node1 children (3) + override child (1)
        Assert.assertEquals(4, merged.getChildren().size());
    }
}
