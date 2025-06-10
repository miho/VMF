package eu.mihosoft.vmftest.diff;

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for the simple diff/merge utility.
 */
public class ModelDiffTest {

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