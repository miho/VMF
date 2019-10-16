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
        {
            EqualsTestModel model1 = EqualsTestModel.newInstance();
            model1.setName("name1");

            model1.setReference(AReference.newBuilder().withName("ref name").build());

            EqualsTestModel model2 = EqualsTestModel.newInstance();
            model2.setName("name1");

            Assert.assertEquals(model1, model2);
        }

        {
            EqualsTestModel model1 = EqualsTestModel.newInstance();
            model1.setName("name1");

            model1.setReference(AReference.newBuilder().withName("ref name 1").build());

            EqualsTestModel model2 = EqualsTestModel.newInstance();
            model2.setName("name1");

            model2.setReference(AReference.newBuilder().withName("ref name 2").build());

            Assert.assertEquals(model1, model2);
        }
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

    @Test
    public void testEqualsContract1() {

        // test equality contract
        // 1.) equal to self

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        model1.setChild(Child.newBuilder().withName("child name 1").build());

        Assert.assertEquals(model1, model1);
    }

    @Test
    public void testEqualsContract2() {

        // test equality contract
        // 2.) symmetric (x eq y == y eq x)

        EqualsTestModel model1 = EqualsTestModel.newInstance();
        model1.setName("name1");

        EqualsTestModel2 model2 = EqualsTestModel2.newInstance();
        model2.setName("name1");

        Assert.assertNotEquals(model1, model2);
        Assert.assertNotEquals(model2, model1);

        // now symmetry with super type

        WithName withName1 = model1;
        WithName withName2 = model2;

        Assert.assertNotEquals(withName1, withName2);

        WithName withName3 = WithName.newBuilder().withName("name1").build();

        Assert.assertNotEquals(withName3, withName1);
        Assert.assertNotEquals(withName3, withName2);
        Assert.assertNotEquals(withName1, withName3);
        Assert.assertNotEquals(withName2, withName3);
    }

    @Test
    public void testEqualsContract3() {

        // test equality contract
        // 3.) transitive (x eq y && y eq z => x eq z)

        EqualsTestModel x = EqualsTestModel.newInstance();
        x.setName("name1");
        x.setChild(Child.newBuilder().withName("child name 1").build());

        EqualsTestModel y = EqualsTestModel.newInstance();
        y.setName("name1");
        y.setChild(Child.newBuilder().withName("child name 1").build());

        EqualsTestModel z = EqualsTestModel.newInstance();
        z.setName("name1");
        z.setChild(Child.newBuilder().withName("child name 1").build());

        Assert.assertEquals(x, y);
        Assert.assertEquals(y, z);
        Assert.assertEquals(x, z);
    }

    @Test
    public void testEqualsAll() {

        // we use ALL equals implementation
        // that is, with identical properties we expect equality
        // even though we are comparing two different objects
        {
            EqualsTestModelAllEq model1 = EqualsTestModelAllEq.
                    newBuilder().withName("my name1").
                    withValue(3).build();

            EqualsTestModelAllEq model2 = EqualsTestModelAllEq.newInstance();
            EqualsTestModelAllEq.newBuilder().applyFrom(model1).applyTo(model2);

            Assert.assertEquals(model1, model2);
        }

        // ALL considers all references
        // and external types. thus, we expect the objects to differ.
        {
            EqualsTestModelAllEq model1 = EqualsTestModelAllEq.newInstance();
            model1.setName("name1");

            model1.setReference(AReference.newBuilder().withName("ref name 1").build());

            EqualsTestModelAllEq model2 = EqualsTestModelAllEq.newInstance();
            model2.setName("name1");

            model1.setReference(AReference.newBuilder().withName("ref name 2").build());

            Assert.assertNotEquals(model1, model2);
        }

    }

    @Test
    public void testEqualsInstance() {

        // we use INSTANCE equals implementation
        // that is, with identical properties we expect objects to differ
        // even though we are comparing two objects with identical data
        {
            EqualsTestModelInstanceEq model1 = EqualsTestModelInstanceEq.
                    newBuilder().withName("my name1").
                    withValue(3).build();

            EqualsTestModelInstanceEq model2 = EqualsTestModelInstanceEq.newInstance();
            EqualsTestModelInstanceEq.newBuilder().applyFrom(model1).applyTo(model2);

            Assert.assertNotEquals(model1, model2);
        }

        // in case of INSTANCE we use the CONTAINMENT_AND_EXTERNAL
        // equals implementation for the vObj.vmf().contents().equals()
        // method
        {
            EqualsTestModelInstanceEq model1 = EqualsTestModelInstanceEq.
                    newBuilder().withName("my name1").
                    withValue(3).build();

            model1.setReference(AReference.newBuilder().withName("ref name 1").build());

            EqualsTestModelInstanceEq model2 = EqualsTestModelInstanceEq.
                newBuilder().withName("my name1").
                withValue(3).build();

            model2.setReference(AReference.newBuilder().withName("ref name 2").build());

            Assert.assertTrue(model1.vmf().content().equals(model2));
        }
    }

}
