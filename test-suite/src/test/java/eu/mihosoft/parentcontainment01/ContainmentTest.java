package eu.mihosoft.parentcontainment01;

import eu.mihosoft.vmftest.parentcontainment01.NumberExpression;
import eu.mihosoft.vmftest.parentcontainment01.OperatorExpression;
import org.junit.Assert;
import org.junit.Test;

public class ContainmentTest {
    @Test
    public void testContainmentBehaviorGetParent() {
        OperatorExpression operatorExpression = OperatorExpression.newInstance();

        NumberExpression leftValue = NumberExpression.newBuilder().withValue(3.2).build();
        NumberExpression rightValue = NumberExpression.newBuilder().withValue(1.2).build();

        System.out.println("left parent before: " + leftValue.getParent());
        System.out.println("right parent before: " + rightValue.getParent());

        Assert.assertEquals(null, operatorExpression.getParent());
        Assert.assertEquals(null, leftValue.getParent());
        Assert.assertEquals(null, rightValue.getParent());

        operatorExpression.setLeft(leftValue);
        operatorExpression.setRight(rightValue);

        System.out.println("left parent after: " + leftValue.getParent());
        System.out.println("right parent after: " + rightValue.getParent());

        Assert.assertEquals(null, operatorExpression.getParent());
        Assert.assertEquals(operatorExpression, leftValue.getParent());
        Assert.assertEquals(operatorExpression, rightValue.getParent());

    }

    @Test
    public void testContainmentBehaviorFindRoot() {
        OperatorExpression root = OperatorExpression.newInstance();

        NumberExpression l0   = NumberExpression.newBuilder().withValue(3.2).build();
        OperatorExpression r0 = OperatorExpression.newInstance();

        root.setLeft(l0);
        root.setRight(r0);

        Assert.assertEquals(root, l0.root());
        Assert.assertEquals(root, r0.root());

        NumberExpression l1 = NumberExpression.newBuilder().withValue(1.2).build();
        NumberExpression r1 = NumberExpression.newBuilder().withValue(5.6).build();

        r0.setLeft(l1);
        r0.setRight(r1);

        Assert.assertEquals(root, l1.root());
        Assert.assertEquals(root, r1.root());
    }
}
