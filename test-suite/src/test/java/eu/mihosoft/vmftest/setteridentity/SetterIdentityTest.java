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

import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
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
    public void sortingContainedChildrenRetainsIdentityAndParent() {
        BinaryExpression parent = BinaryExpression.newInstance();
        NumberExpression first = number(2);
        NumberExpression second = number(1);
        NumberExpression third = number(2);
        Assert.assertEquals(first, third);
        parent.getArguments().addAll(List.of(first, second, third));
        var changes = parent.vmf().changes();
        changes.start();
        changes.startTransaction();

        parent.getArguments().sort(Comparator.comparingDouble(argument -> ((NumberExpression) argument).getValue()));

        changes.publishTransaction();
        assertArgumentOrder(parent, second, first, third);
        assertArgumentChangesRecorded(parent, first, second, third);
        changes.stop();

        var reorder = changes.transactions().get(0);
        reorder.undo();
        assertArgumentOrder(parent, first, second, third);
        reorder.changes().forEach(change -> change.apply(change.object()));
        assertArgumentOrder(parent, second, first, third);
    }

    @Test
    public void swappingContainedChildrenRetainsIdentityAndParent() {
        BinaryExpression parent = BinaryExpression.newInstance();
        NumberExpression first = number(1);
        NumberExpression second = number(2);
        NumberExpression third = number(1);
        Assert.assertEquals(first, third);
        parent.getArguments().addAll(List.of(first, second, third));
        var changes = parent.vmf().changes();
        changes.start();
        changes.startTransaction();

        Collections.swap(parent.getArguments(), 0, 2);

        changes.publishTransaction();
        assertArgumentOrder(parent, third, second, first);
        assertArgumentChangesRecorded(parent, first, second, third);
        changes.stop();

        var reorder = changes.transactions().get(0);
        reorder.undo();
        assertArgumentOrder(parent, first, second, third);
        reorder.changes().forEach(change -> change.apply(change.object()));
        assertArgumentOrder(parent, third, second, first);
    }

    @Test
    public void sortingEqualButDistinctChildrenPreservesIdentityOrderInNativeReplay() {
        BinaryExpression parent = BinaryExpression.newInstance();
        NumberExpression first = number(1);
        NumberExpression second = number(1);
        NumberExpression third = number(2);
        NumberExpression stationary = number(3);
        Assert.assertEquals(first, second);
        Assert.assertNotSame(first, second);
        parent.getArguments().addAll(List.of(first, stationary, second, third));

        var rank = new IdentityHashMap<Expression, Integer>();
        rank.put(second, 0);
        rank.put(stationary, 1);
        rank.put(third, 2);
        rank.put(first, 3);
        var changes = parent.vmf().changes();
        changes.start();
        changes.startTransaction();

        parent.getArguments().sort(Comparator.comparingInt(rank::get));

        changes.publishTransaction();
        changes.stop();
        assertArgumentOrder(parent, second, stationary, third, first);

        Assert.assertEquals(1, changes.transactions().size());
        var reorder = changes.transactions().get(0);
        Assert.assertEquals(1, reorder.changes().size());
        var change = reorder.changes().get(0);
        var event = change.listChange().orElseThrow();
        Assert.assertTrue(event.wasSet());
        Assert.assertArrayEquals(new int[]{0, 2, 3}, event.added().indices());
        Assert.assertArrayEquals(new int[]{0, 2, 3}, event.removed().indices());
        Assert.assertSame(first, event.removed().elements().get(0));
        Assert.assertSame(second, event.added().elements().get(0));

        reorder.undo();
        assertArgumentOrder(parent, first, stationary, second, third);
        change.apply(parent);
        assertArgumentOrder(parent, second, stationary, third, first);
    }

    @Test
    public void replacingAnArgumentCanMoveAChildFromAnotherPropertyOfTheSameParent() {
        BinaryExpression parent = BinaryExpression.newInstance();
        NumberExpression oldArgument = number(1);
        NumberExpression left = number(2);
        parent.getArguments().add(oldArgument);
        parent.setLeft(left);

        Assert.assertSame(oldArgument, parent.getArguments().set(0, left));

        assertArgumentOrder(parent, left);
        Assert.assertNull(parent.getLeft());
        Assert.assertNull(oldArgument.getParent());
    }

    @Test
    public void reparentingReleasesEveryOldOccurrenceWithoutRemovingEqualSiblings() {
        BinaryExpression oldParent = BinaryExpression.newInstance();
        BinaryExpression newParent = BinaryExpression.newInstance();
        NumberExpression child = number(1);
        NumberExpression replaced = number(2);
        NumberExpression equalSibling = number(1);
        oldParent.getArguments().addAll(List.of(child, replaced, equalSibling));
        oldParent.getArguments().set(1, child);
        assertArgumentOrder(oldParent, child, child, equalSibling);
        Assert.assertNull(replaced.getParent());

        newParent.getArguments().add(child);

        assertArgumentOrder(oldParent, equalSibling);
        assertArgumentOrder(newParent, child);
    }

    private static void assertArgumentOrder(BinaryExpression parent, Expression... expected) {
        Assert.assertEquals(expected.length, parent.getArguments().size());
        for (int i = 0; i < expected.length; i++) {
            Assert.assertSame(expected[i], parent.getArguments().get(i));
            Assert.assertSame(parent, expected[i].getParent());
        }
    }

    private static void assertArgumentChangesRecorded(BinaryExpression parent, NumberExpression... arguments) {
        for (NumberExpression argument : arguments) {
            argument.setValue(argument.getValue() + 1);
            Assert.assertTrue("the reordered child must still be recorded by the parent",
                    parent.vmf().changes().all().stream().anyMatch(change ->
                            change.object() == argument && "value".equals(change.propertyName())));
        }
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
