package eu.mihosoft.vmftest.builders;

import org.junit.Assert;
import org.junit.Test;

public class BuilderTest {
    @Test
    public void testWithNestedBuilders() {

        AClass.Builder b = AClass.newBuilder()
            .withName("my name")
            .withIds("id1", "id2", "id3")
            .withChildren(
                // lazy also for properties
                Child.newBuilder().withValue(1),
                Child.newBuilder().withValue(2),
                Child.newBuilder().withValue(3)
            )
            .withChild(
                // lazy also for properties
                Child2.newBuilder().withValue(4)
            );

        AClass anInstance = b.build();

        // junit assert values of anInstance are correct according to the builder

        Assert.assertEquals("my name", anInstance.getName());
        Assert.assertEquals(3, anInstance.getIds().size());
        Assert.assertEquals("id1", anInstance.getIds().get(0));
        Assert.assertEquals("id2", anInstance.getIds().get(1));
        Assert.assertEquals("id3", anInstance.getIds().get(2));
        Assert.assertEquals(3, anInstance.getChildren().size());
        Assert.assertEquals(1, anInstance.getChildren().get(0).getValue());
        Assert.assertEquals(2, anInstance.getChildren().get(1).getValue());
        Assert.assertEquals(3, anInstance.getChildren().get(2).getValue());

        Assert.assertEquals(4, anInstance.getChild().getValue());
    }

    @Test
    public void testWithProperties() {

        AClass.Builder b = AClass.newBuilder()
            .withName("my name")
            .withIds("id1", "id2", "id3")
            .withChildren(
                // properties are instantiated immediately
                Child.newBuilder().withValue(1).build(),
                Child.newBuilder().withValue(2).build(),
                Child.newBuilder().withValue(3).build()
            )
            .withChild(
                // properties are instantiated immediately
                Child2.newBuilder().withValue(4).build()
            );

        AClass anInstance = b.build();

        // junit assert values of anInstance are correct according to the builder

        Assert.assertEquals("my name", anInstance.getName());
        Assert.assertEquals(3, anInstance.getIds().size());
        Assert.assertEquals("id1", anInstance.getIds().get(0));
        Assert.assertEquals("id2", anInstance.getIds().get(1));
        Assert.assertEquals("id3", anInstance.getIds().get(2));
        Assert.assertEquals(3, anInstance.getChildren().size());
        Assert.assertEquals(1, anInstance.getChildren().get(0).getValue());
        Assert.assertEquals(2, anInstance.getChildren().get(1).getValue());
        Assert.assertEquals(3, anInstance.getChildren().get(2).getValue());


        Assert.assertEquals(4, anInstance.getChild().getValue());
    }
}
