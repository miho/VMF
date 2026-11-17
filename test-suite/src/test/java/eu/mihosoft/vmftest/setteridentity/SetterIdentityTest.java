/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.setteridentity;

import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Setters compare model-typed values by identity (not by {@code equals}),
 * children can be contained polymorphically through an opposite declared in
 * a base type, and moving a child out of a list removes it by identity.
 */
public class SetterIdentityTest {

    private static NumberExpression number(double value) {
        return NumberExpression.newBuilder().withValue(value).build();
    }

    private static BinaryExpression binary(String operator, Expression left, Expression right) {
        return BinaryExpression.newBuilder().withOperator(operator).withLeft(left).withRight(right).build();
    }

    @Test
    public void structurallyEqualObjectsAreEqualButDistinct() {
        NumberExpression one = number(1);
        NumberExpression anotherOne = number(1);
        NumberExpression four = number(4);

        Assert.assertEquals(one, anotherOne);
        Assert.assertEquals(one.hashCode(), anotherOne.hashCode());
        Assert.assertNotSame(one, anotherOne);
        Assert.assertNotEquals(one, four);
    }

    @Test
    public void structuralEqualsCoversContainedChildren() {
        BinaryExpression sum = binary("+", number(1), number(2));
        BinaryExpression sameSum = binary("+", number(1), number(2));
        BinaryExpression otherSum = binary("+", number(1), number(4));

        Assert.assertEquals(sum, sameSum);
        Assert.assertNotEquals(sum, otherSum);
    }

    @Test
    public void setterReplacesStructurallyEqualReference() {
        Holder holder = Holder.newInstance();
        NumberExpression first = number(1);
        NumberExpression second = number(1);
        Assert.assertEquals(first, second);

        holder.setValue(first);
        int[] changes = new int[1];
        holder.vmf().changes().addListener(c -> changes[0]++, false);

        holder.setValue(second);

        Assert.assertSame("a different but equal object must replace the reference", second, holder.getValue());
        Assert.assertEquals("the replacement must fire a change", 1, changes[0]);

        holder.setValue(second);
        Assert.assertEquals("setting the identical object again is a no-op", 1, changes[0]);
    }

    @Test
    public void containedSetterReplacesStructurallyEqualChild() {
        BinaryExpression expression = BinaryExpression.newInstance();
        NumberExpression first = number(1);
        NumberExpression second = number(1);

        expression.setLeft(first);
        expression.setLeft(second);

        Assert.assertSame(second, expression.getLeft());
        Assert.assertSame(expression, second.getParent());
        Assert.assertNull(first.getParent());
    }

    @Test
    public void oppositeDeclaredInBaseTypeMaintainsParent() {
        BinaryExpression expression = BinaryExpression.newInstance();
        NumberExpression left = number(1);
        NumberExpression argument = number(2);

        Assert.assertNull(left.getParent());

        expression.setLeft(left);
        expression.getArguments().add(argument);

        Assert.assertSame(expression, left.getParent());
        Assert.assertSame(expression, argument.getParent());

        expression.setLeft(null);
        expression.getArguments().remove(0);

        Assert.assertNull(left.getParent());
        Assert.assertNull(argument.getParent());
    }

    @Test
    public void movingPolymorphicChildClearsPreviousSlot() {
        BinaryExpression a = BinaryExpression.newInstance();
        BinaryExpression b = BinaryExpression.newInstance();
        BinaryExpression c = BinaryExpression.newInstance();
        NumberExpression child = number(1);

        a.setLeft(child);
        b.setRight(child);

        Assert.assertNull("the previous container must release the child", a.getLeft());
        Assert.assertSame(child, b.getRight());
        Assert.assertSame(b, child.getParent());

        c.getArguments().add(child);

        Assert.assertNull("the previous container must release the child", b.getRight());
        Assert.assertEquals(1, c.getArguments().size());
        Assert.assertSame(child, c.getArguments().get(0));
        Assert.assertSame(c, child.getParent());

        a.setRight(child);

        Assert.assertTrue("the previous list must release the child", c.getArguments().isEmpty());
        Assert.assertSame(child, a.getRight());
        Assert.assertSame(a, child.getParent());
    }

    @Test
    public void movingChildOutOfListRemovesByIdentity() {
        BinaryExpression a = BinaryExpression.newInstance();
        BinaryExpression b = BinaryExpression.newInstance();
        NumberExpression first = number(1);
        NumberExpression second = number(1);
        Assert.assertEquals(first, second);

        a.getArguments().add(first);
        a.getArguments().add(second);

        b.setLeft(second);

        Assert.assertEquals(1, a.getArguments().size());
        Assert.assertSame("the equal sibling must stay in the list", first, a.getArguments().get(0));
        Assert.assertSame(a, first.getParent());
        Assert.assertSame(b, second.getParent());
    }

    @Test
    public void containmentTreeTraversalStaysInSubtree() {
        BinaryExpression inner = binary("*", number(1), number(2));
        BinaryExpression root = binary("+", inner, number(3));

        List<VObject> subtree = inner.vmf().content().
                stream(Element.class, VIterator.IterationStrategy.CONTAINMENT_TREE).
                collect(Collectors.toList());

        Assert.assertEquals(3, subtree.size());
        Assert.assertSame(inner, subtree.get(0));
        Assert.assertSame(inner.getLeft(), subtree.get(1));
        Assert.assertSame(inner.getRight(), subtree.get(2));

        List<VObject> defaultTraversal = inner.vmf().content().stream().collect(Collectors.toList());

        Assert.assertTrue("the default traversal must not escape through the container",
                defaultTraversal.stream().noneMatch(o -> o == root || o == root.getRight()));
        Assert.assertEquals(3, defaultTraversal.size());

        List<VObject> wholeTree = root.vmf().content().
                stream(Element.class, VIterator.IterationStrategy.CONTAINMENT_TREE).
                collect(Collectors.toList());

        Assert.assertEquals(5, wholeTree.size());
    }

    @Test
    public void deepCopyCopiesContainedChildren() {
        BinaryExpression root = binary("+", binary("*", number(1), number(2)), number(3));

        BinaryExpression copy = root.clone();

        Assert.assertEquals(root, copy);
        Assert.assertNotSame(root.getLeft(), copy.getLeft());
        Assert.assertSame(copy, copy.getLeft().getParent());
        Assert.assertSame(root, root.getLeft().getParent());
    }
}
