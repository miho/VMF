package eu.mihosoft.vmftest.equals;

import org.junit.Assert;
import org.junit.Test;

public class EqualsTest {

    @Test
    public void testEquals1() {

        // we use CONTAINMENT_AND_EXTERNAL equals implementation
        // that is, with identical properties we expect equality
        // even though we are comparing two different objects

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        EqualsTestModel model2 = EqualsTestModel.newInstance();
        model2.setName("name1");

        Assert.assertEquals(model1, model2);
    }
    @Test
    public void testEquals2() {

        // if the properties are not equal we expect
        // the objects to be not equal

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        EqualsTestModel model2 = EqualsTestModel.newInstance();
        model2.setName("name2");

        Assert.assertNotEquals(model1, model2);
    }
    @Test
    public void testEquals3() {

        // CONTAINMENT_AND_EXTERNAL only considers containment references
        // and external types. thus, we expect equality.

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        model1.setReference(AReference.newBuilder().withName("ref name").build());

        EqualsTestModel model2 = EqualsTestModel.newInstance();
        model2.setName("name1");

        Assert.assertEquals(model1, model2);
    }
    @Test
    public void testEquals4() {

        // if the child property is differs then we expect the
        // objects to be not equal

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        model1.setChild(Child.newBuilder().withName("child name").build());

        EqualsTestModel model2 = EqualsTestModel.newInstance();
        model2.setName("name1");

        Assert.assertNotEquals(model1, model2);
    }
    @Test
    public void testEquals5() {

        // but if both children are equal then both objects are equal

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        model1.setChild(Child.newBuilder().withName("child name").build());

        EqualsTestModel model2 = EqualsTestModel.newInstance();
        model2.setName("name1");

        model2.setChild(Child.newBuilder().withName("child name").build());

        Assert.assertEquals(model1, model2);
    }

    @Test
    public void testEquals6() {

        // the children are equal even if the parents are not. this is due to the
        // fact that both children reference the parent as part of a containment
        // reference (containment parents are ignored).

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        model1.setChild(Child.newBuilder().withName("child name").build());

        EqualsTestModel model2 = EqualsTestModel.newInstance();
        model2.setName("name2");

        model2.setChild(Child.newBuilder().withName("child name").build());

        Assert.assertEquals(model1.getChild(), model2.getChild());
    }

    @Test
    public void testEquals7() {

        // children are not equal if their properties are not equal though.

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        model1.setChild(Child.newBuilder().withName("child name 1").build());

        EqualsTestModel model2 = EqualsTestModel.newInstance();
        model2.setName("name2");

        model2.setChild(Child.newBuilder().withName("child name 2").build());

        Assert.assertNotEquals(model1.getChild(), model2.getChild());
    }

}
