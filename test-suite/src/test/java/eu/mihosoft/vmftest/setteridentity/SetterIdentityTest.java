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

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.TraversalListener;
import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.NoSuchElementException;
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
    public void recordedContainmentListEditsRejectAnotherParent() {
        BinaryExpression source = BinaryExpression.newInstance();
        NumberExpression first = number(1);
        NumberExpression second = number(2);
        source.getArguments().addAll(List.of(first, second));
        BinaryExpression target = source.clone();
        Expression targetFirst = target.getArguments().get(0);
        Expression targetSecond = target.getArguments().get(1);
        Assert.assertNotSame(first, targetFirst);
        Assert.assertNotSame(second, targetSecond);

        NumberExpression appended = number(3);
        NumberExpression replacement = number(4);
        var changes = source.vmf().changes();
        changes.start();
        source.getArguments().add(appended);
        source.getArguments().set(0, replacement);
        source.getArguments().remove(1);
        changes.stop();

        List<Change> records = changes.all().stream()
                .filter(c -> c.object() == source && c.propertyName().equals("arguments"))
                .collect(Collectors.toList());
        Assert.assertEquals(3, records.size());
        Assert.assertTrue(records.get(0).listChange().orElseThrow().wasAdded());
        Assert.assertTrue(records.get(1).listChange().orElseThrow().wasSet());
        Assert.assertTrue(records.get(2).listChange().orElseThrow().wasRemoved());
        for (Change record : records) {
            Assert.assertThrows(IllegalArgumentException.class, () -> record.apply(target));
        }
        assertArgumentOrder(source, replacement, appended);
        assertArgumentOrder(target, targetFirst, targetSecond);
    }

    @Test
    public void recordedScalarChangeAppliesToExplicitNestedCounterpart() {
        NumberExpression sourceChild = number(1);
        BinaryExpression source = binary("+", sourceChild, number(2));
        BinaryExpression target = source.clone();
        NumberExpression targetChild = (NumberExpression) target.getLeft();
        var changes = source.vmf().changes();
        changes.start();
        sourceChild.setValue(3);
        changes.stop();

        List<Change> records = List.copyOf(changes.all());
        Assert.assertEquals(1, records.size());
        Assert.assertSame(sourceChild, records.get(0).object());
        records.get(0).apply(targetChild);
        Assert.assertEquals(3, sourceChild.getValue(), 0);
        Assert.assertEquals(3, targetChild.getValue(), 0);
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
    public void containmentTraversalYieldsRepeatedOwningSlotsAndExpandsOnce() {
        var branch = binary("*", number(1), number(2));
        var tail = number(4);
        var root = BinaryExpression.newInstance();
        root.getArguments().addAll(java.util.Arrays.asList(branch, null, number(3), tail));
        root.getArguments().set(2, branch);

        assertIdentityTraversal(root, VIterator.IterationStrategy.UNIQUE_NODE,
                root, branch, branch.getLeft(), branch.getRight(), tail);
        assertIdentityTraversal(root, VIterator.IterationStrategy.UNIQUE_PROPERTY,
                root, branch, branch.getLeft(), branch.getRight(), branch, tail);
        assertIdentityTraversal(root, VIterator.IterationStrategy.CONTAINMENT_TREE,
                root, branch, branch.getLeft(), branch.getRight(), branch, tail);

        var view = root.asReadOnly();
        var branchView = branch.asReadOnly();
        assertIdentityTraversal(view, VIterator.IterationStrategy.CONTAINMENT_TREE,
                view, branchView, branchView.getLeft(), branchView.getRight(), branchView, tail.asReadOnly());
    }

    private static void assertIdentityTraversal(VObject root, VIterator.IterationStrategy strategy, VObject... expected) {
        var actual = root.vmf().content().stream(strategy).collect(Collectors.toList());
        Assert.assertEquals(expected.length, actual.size());
        for (int i = 0; i < expected.length; i++) Assert.assertSame(expected[i], actual.get(i));
    }

    @Test
    public void containmentCursorPrunesTheDetachedLookaheadFrameAfterTheOwningEdit() {
        var branch = binary("*", number(1), number(2));
        var replacement = binary("*", number(3), number(4));
        var sibling = number(5);
        var root = BinaryExpression.newBuilder().withArguments(branch, sibling).build();
        var trace = new ArrayList<String>();
        var cursor = VIterator.of(root, new TraversalListener() {
            @Override public void onEnter(VObject value) {
                if (value == root) trace.add("enter root");
                else if (value == branch) trace.add("enter branch");
                else if (value == sibling) trace.add("enter sibling");
                else Assert.fail("Unexpected traversal into a detached or replacement branch");
            }

            @Override public void onExit(VObject value) {
                if (value != branch) return;
                Assert.assertNull(branch.getParent());
                Assert.assertSame(replacement, root.getArguments().get(0));
                Assert.assertSame(root, replacement.getParent());
                trace.add("exit branch");
            }
        }, VIterator.IterationStrategy.CONTAINMENT_TREE);
        var edits = root.vmf().changes().addLocalListener(change -> {
            if ("arguments".equals(change.propertyName())) trace.add("replace branch");
        });
        try {
            assertCursorOnBranch(cursor, root, branch);
            cursor.set(replacement);
            Assert.assertEquals(List.of("enter root", "enter branch", "replace branch", "exit branch"), trace);
            Assert.assertTrue(cursor.hasNext());
            Assert.assertSame(sibling, cursor.next());
            Assert.assertFalse(cursor.hasNext());
            Assert.assertEquals(List.of("enter root", "enter branch", "replace branch", "exit branch", "enter sibling"), trace);
        } finally {
            edits.unsubscribe();
        }
    }

    @Test
    public void containmentCursorKeepsItsFrameWhenTheSameChildOrAnotherOwnedSlotRemains() {
        for (boolean duplicate : new boolean[] {false, true}) {
            var branch = binary("*", number(1), number(2));
            var sibling = number(5);
            var root = duplicate
                    ? BinaryExpression.newBuilder().withArguments(branch, number(9), sibling).build()
                    : BinaryExpression.newBuilder().withArguments(branch, sibling).build();
            if (duplicate) root.getArguments().set(1, branch);
            var exits = new ArrayList<VObject>();
            var cursor = VIterator.of(root, new TraversalListener() {
                @Override public void onEnter(VObject value) { }
                @Override public void onExit(VObject value) { exits.add(value); }
            }, VIterator.IterationStrategy.CONTAINMENT_TREE);

            assertCursorOnBranch(cursor, root, branch);
            var selected = duplicate ? binary("*", number(3), number(4)) : branch;
            cursor.set(selected);
            Assert.assertSame(selected, root.getArguments().get(0));
            Assert.assertSame(root, branch.getParent());
            Assert.assertTrue(exits.stream().noneMatch(value -> value == branch));
            Assert.assertSame(branch.getLeft(), cursor.next());
            Assert.assertSame(branch.getRight(), cursor.next());
            if (duplicate) {
                Assert.assertSame(branch, root.getArguments().get(1));
                Assert.assertSame(branch, cursor.next());
            }
            Assert.assertSame(sibling, cursor.next());
            Assert.assertFalse(cursor.hasNext());
            Assert.assertEquals(1L, exits.stream().filter(value -> value == branch).count());
        }
    }

    @Test
    public void containmentCursorFinishesPruningEvenWhenReplacementAndExitObserversThrow() {
        var branch = binary("*", number(1), number(2));
        var replacement = binary("*", number(3), number(4));
        var sibling = number(5);
        var root = BinaryExpression.newBuilder().withArguments(branch, sibling).build();
        var editFailure = new IllegalStateException("owning edit");
        var exitFailure = new IllegalStateException("cursor exit");
        var exits = new ArrayList<VObject>();
        var cursor = VIterator.of(root, new TraversalListener() {
            @Override public void onEnter(VObject value) { }
            @Override public void onExit(VObject value) {
                if (value != branch) return;
                exits.add(value);
                throw exitFailure;
            }
        }, VIterator.IterationStrategy.CONTAINMENT_TREE);
        var edits = root.vmf().changes().addLocalListener(change -> {
            if ("arguments".equals(change.propertyName())) throw editFailure;
        });
        try {
            assertCursorOnBranch(cursor, root, branch);
            var thrown = Assert.assertThrows(IllegalStateException.class, () -> cursor.set(replacement));
            Assert.assertSame(editFailure, thrown);
            Assert.assertArrayEquals(new Throwable[] {exitFailure}, thrown.getSuppressed());
            Assert.assertSame(replacement, root.getArguments().get(0));
            Assert.assertNull(branch.getParent());
            Assert.assertSame(sibling, cursor.next());
            Assert.assertFalse(cursor.hasNext());
            Assert.assertEquals(1, exits.size());
        } finally {
            edits.unsubscribe();
        }
    }

    @Test
    public void rejectedContainmentCursorReplacementKeepsTheLiveFrame() {
        var branch = binary("*", number(1), number(2));
        var sibling = number(5);
        var root = BinaryExpression.newBuilder().withArguments(branch, sibling).build();
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        assertCursorOnBranch(cursor, root, branch);
        Assert.assertThrows(IllegalArgumentException.class, () -> cursor.set(root));
        Assert.assertSame(branch, root.getArguments().get(0));
        Assert.assertSame(root, branch.getParent());
        Assert.assertSame(branch.getLeft(), cursor.next());
        Assert.assertSame(branch.getRight(), cursor.next());
        Assert.assertSame(sibling, cursor.next());
        Assert.assertFalse(cursor.hasNext());
    }

    private static void assertCursorOnBranch(VIterator cursor, BinaryExpression root, BinaryExpression branch) {
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(branch, cursor.next());
        Assert.assertTrue(cursor.hasNext());
        Assert.assertTrue(cursor.hasNext());
    }

    @Test
    public void containmentCursorEditsTheRepeatedOwningSlotInsteadOfItsDescendant() {
        var branch = binary("*", number(1), number(2));
        var right = branch.getRight();
        var sibling = number(5);
        var root = BinaryExpression.newBuilder().withArguments(branch, number(9), sibling).build();
        root.getArguments().set(1, branch);

        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(branch, cursor.next());
        Assert.assertSame(branch.getLeft(), cursor.next());
        Assert.assertSame(right, cursor.next());
        Assert.assertSame(branch, cursor.next());
        Assert.assertTrue(cursor.hasNext());
        Assert.assertTrue(cursor.hasNext());
        Assert.assertTrue(cursor.isAddSupported());

        var replacement = number(3);
        cursor.set(replacement);
        Assert.assertSame(branch, root.getArguments().get(0));
        Assert.assertSame(replacement, root.getArguments().get(1));
        Assert.assertSame(right, branch.getRight());
        Assert.assertSame(root, branch.getParent());

        var inserted = number(4);
        cursor.add(inserted);
        Assert.assertSame(inserted, root.getArguments().get(2));
        Assert.assertSame(sibling, cursor.next());
        Assert.assertFalse(cursor.hasNext());
    }

    @Test
    public void containmentCursorCanEditTheLastListSlotAfterTrailingLookahead() {
        var first = number(1);
        var last = number(2);
        var root = BinaryExpression.newInstance();
        root.getArguments().addAll(java.util.Arrays.asList(first, last, null));
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(first, cursor.next());
        Assert.assertSame(last, cursor.next());
        Assert.assertFalse(cursor.hasNext());
        Assert.assertFalse(cursor.hasNext());
        Assert.assertTrue(cursor.isAddSupported());

        var replacement = number(3);
        cursor.set(replacement);
        Assert.assertSame(replacement, root.getArguments().get(1));
        Assert.assertNull(root.getLeft());
        Assert.assertNull(last.getParent());

        var inserted = number(4);
        cursor.add(inserted);
        Assert.assertSame(inserted, root.getArguments().get(2));
        Assert.assertNull(root.getArguments().get(3));
        Assert.assertFalse(cursor.hasNext());
    }

    @Test
    public void containmentCursorEditsThePoppedDescendantSlotWithoutReopeningItsFrame() {
        var child = number(1);
        var branch = BinaryExpression.newBuilder().withArguments(child).build();
        var sibling = number(5);
        var root = BinaryExpression.newBuilder().withArguments(branch, sibling).build();
        var names = new IdentityHashMap<VObject, String>();
        names.put(root, "root");
        names.put(branch, "branch");
        names.put(child, "child");
        names.put(sibling, "sibling");
        var trace = new ArrayList<String>();
        var cursor = VIterator.of(root, new TraversalListener() {
            @Override public void onEnter(VObject value) { trace.add("enter " + names.get(value)); }
            @Override public void onExit(VObject value) { trace.add("exit " + names.get(value)); }
        }, VIterator.IterationStrategy.CONTAINMENT_TREE);

        Assert.assertSame(root, cursor.next());
        Assert.assertSame(branch, cursor.next());
        Assert.assertSame(child, cursor.next());
        Assert.assertTrue(cursor.hasNext());
        Assert.assertTrue(cursor.hasNext());
        Assert.assertEquals(List.of("enter root", "enter branch", "enter child", "exit child", "exit branch"), trace);
        Assert.assertTrue(cursor.isAddSupported());

        var edits = branch.vmf().changes().addLocalListener(change -> trace.add("edit " + change.propertyName()));
        try {
            var replacement = number(3);
            cursor.set(replacement);
            Assert.assertSame(replacement, branch.getArguments().get(0));
            Assert.assertNull(branch.getLeft());
            Assert.assertSame(sibling, cursor.next());
            Assert.assertFalse(cursor.hasNext());
        } finally {
            edits.unsubscribe();
        }
        Assert.assertEquals(List.of("enter root", "enter branch", "enter child", "exit child", "exit branch",
                "edit arguments", "enter sibling", "exit sibling", "exit root"), trace);
    }

    @Test
    public void nextThrowsAfterTraversingAnEmptyModelOrSingleton() {
        var empty = BinaryExpression.newInstance();
        empty.getArguments().add(null);
        for (var root : List.<VObject>of(empty, number(1))) {
            for (var strategy : VIterator.IterationStrategy.values()) {
                var trace = new ArrayList<String>();
                var cursor = VIterator.of(root, recordTraversal(trace, root), strategy);
                Assert.assertTrue(cursor.hasNext());
                Assert.assertSame(root, cursor.next());
                Assert.assertThrows(NoSuchElementException.class, cursor::next);
                Assert.assertFalse(cursor.hasNext());
                Assert.assertThrows(NoSuchElementException.class, cursor::next);
                Assert.assertEquals(List.of("enter root", "exit root"), trace);
            }
        }
    }

    @Test
    public void nextThrowsAfterReadOnlyTraversalWithoutAnotherListenerEvent() {
        var root = BinaryExpression.newBuilder().withArguments(number(1)).build().asReadOnly();
        var child = root.getArguments().get(0);
        var trace = new ArrayList<String>();
        var cursor = VIterator.of(root, recordTraversal(trace, root), VIterator.IterationStrategy.CONTAINMENT_TREE);

        // Use next directly to ensure exhaustion closes any remaining root frame.
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(child, cursor.next());
        Assert.assertThrows(NoSuchElementException.class, cursor::next);
        Assert.assertFalse(cursor.hasNext());
        Assert.assertThrows(NoSuchElementException.class, cursor::next);
        Assert.assertEquals(List.of("enter root", "enter child", "exit child", "exit root"), trace);
        Assert.assertThrows(RuntimeException.class, () -> cursor.set(number(2)));
        Assert.assertSame(child, root.getArguments().get(0));
    }

    @Test
    public void nextAfterExhaustionDoesNotChangeTheSelectedRepeatedOwningSlot() {
        var terminal = number(1);
        var root = BinaryExpression.newBuilder().withArguments(terminal, number(9)).build();
        root.getArguments().set(1, terminal);
        root.getArguments().add(null);
        var trace = new ArrayList<String>();
        var cursor = VIterator.of(root, recordTraversal(trace, root), VIterator.IterationStrategy.CONTAINMENT_TREE);

        Assert.assertSame(root, cursor.next());
        Assert.assertSame(terminal, cursor.next());
        Assert.assertSame(terminal, cursor.next());
        Assert.assertFalse(cursor.hasNext());
        Assert.assertThrows(NoSuchElementException.class, cursor::next);
        Assert.assertThrows(NoSuchElementException.class, cursor::next);
        Assert.assertTrue(cursor.isAddSupported());

        var replacement = number(2);
        cursor.set(replacement);
        Assert.assertSame(terminal, root.getArguments().get(0));
        Assert.assertSame(replacement, root.getArguments().get(1));
        Assert.assertSame(root, terminal.getParent());
        Assert.assertNull(root.getLeft());

        var inserted = number(3);
        cursor.add(inserted);
        Assert.assertSame(inserted, root.getArguments().get(2));
        Assert.assertNull(root.getArguments().get(3));
        Assert.assertFalse(cursor.hasNext());
        Assert.assertThrows(NoSuchElementException.class, cursor::next);
        Assert.assertEquals(List.of("enter root", "enter child", "exit child", "exit root"), trace);
    }

    private static TraversalListener recordTraversal(List<String> trace, VObject root) {
        return new TraversalListener() {
            @Override public boolean isIgnoreNullObjects() { return false; }
            @Override public void onEnter(VObject value) { trace.add("enter " + name(value)); }
            @Override public void onExit(VObject value) { trace.add("exit " + name(value)); }

            private String name(VObject value) {
                return value == null ? "null" : value == root ? "root" : "child";
            }
        };
    }

    @Test
    public void cursorRejectsAReplacementAndInsertionAfterAnExternalListSet() {
        var selected = number(1);
        var second = number(2);
        var root = BinaryExpression.newBuilder().withArguments(selected, second).build();
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(selected, cursor.next());
        Assert.assertTrue(cursor.hasNext());

        var external = number(1);
        Assert.assertEquals(selected, external);
        root.getArguments().set(0, external);
        var attempted = number(3);

        Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.set(attempted));
        Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.add(attempted));
        Assert.assertFalse(cursor.isAddSupported());
        Assert.assertSame(external, root.getArguments().get(0));
        Assert.assertSame(root, external.getParent());
        Assert.assertSame(second, root.getArguments().get(1));
        Assert.assertNull(selected.getParent());
        Assert.assertNull(attempted.getParent());

        Assert.assertSame(second, cursor.next());
        Assert.assertTrue(cursor.isAddSupported());
        cursor.set(attempted);
        Assert.assertSame(attempted, root.getArguments().get(1));
        Assert.assertSame(root, attempted.getParent());
    }

    @Test
    public void cursorRejectsAReplacementAfterAnExternalSingularSet() {
        var selected = binary("+", number(1), number(2));
        var external = binary("+", number(1), number(2));
        Assert.assertEquals(selected, external);
        var tail = number(3);
        var root = binary("*", selected, tail);
        var trace = new ArrayList<String>();
        var cursor = VIterator.of(root, recordTraversal(trace, root), VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(selected, cursor.next());
        Assert.assertTrue(cursor.hasNext());
        root.setLeft(external);
        var attempted = number(4);
        Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.set(attempted));
        Assert.assertSame(external, root.getLeft());
        Assert.assertSame(root, external.getParent());
        Assert.assertSame(tail, root.getRight());
        Assert.assertNull(selected.getParent());
        Assert.assertNull(attempted.getParent());
        Assert.assertEquals(List.of("enter root", "enter child"), trace);
    }

    @Test
    public void cursorStillEditsItsOwnReplacementAndKeepsStructuralFailFastBehavior() {
        var selected = number(1);
        var second = number(2);
        var root = BinaryExpression.newBuilder().withArguments(selected, second).build();
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(selected, cursor.next());

        cursor.set(null);
        var replacement = number(3);
        cursor.set(replacement);
        Assert.assertSame(replacement, root.getArguments().get(0));
        root.getArguments().add(0, selected);
        Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.set(null));
        Assert.assertSame(selected, root.getArguments().get(0));
        Assert.assertSame(replacement, root.getArguments().get(1));
    }

    @Test
    public void cursorKeepsItsCommittedReplacementSelectedWhenAnObserverThrows() {
        var selected = number(1);
        var root = BinaryExpression.newBuilder().withArguments(selected, number(2)).build();
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(selected, cursor.next());

        var failure = new IllegalStateException("observer");
        var subscription = root.vmf().changes().addLocalListener(change -> { throw failure; });
        var replacement = number(3);
        try {
            Assert.assertSame(failure, Assert.assertThrows(IllegalStateException.class, () -> cursor.set(replacement)));
        } finally {
            subscription.unsubscribe();
        }
        Assert.assertSame(replacement, root.getArguments().get(0));
        Assert.assertSame(root, replacement.getParent());
        Assert.assertTrue(cursor.isAddSupported());
        cursor.set(null);
        Assert.assertNull(root.getArguments().get(0));
        Assert.assertNull(replacement.getParent());
    }

    @Test
    public void cursorRejectsARestoredSingularSlotIncludingItsOwnSelectedNull() {
        for (boolean clearFirst : List.of(false, true)) {
            var selected = number(1);
            var root = binary("+", selected, null);
            var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
            Assert.assertSame(root, cursor.next());
            Assert.assertSame(selected, cursor.next());
            if (clearFirst) cursor.set(null);
            var expected = root.getLeft();

            var transientChild = number(2);
            root.setLeft(transientChild);
            root.setLeft(expected);

            var attempted = number(3);
            Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.set(attempted));
            Assert.assertSame(expected, root.getLeft());
            Assert.assertNull(transientChild.getParent());
            Assert.assertNull(attempted.getParent());
        }
    }

    @Test
    public void cursorIgnoresASingularNoOpAndAnotherSlotMutation() {
        var selected = number(1);
        var root = binary("+", selected, number(2));
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(selected, cursor.next());

        var sibling = number(3);
        root.setLeft(selected);
        root.setRight(sibling);
        var replacement = number(4);
        cursor.set(replacement);
        Assert.assertSame(replacement, root.getLeft());
        Assert.assertSame(sibling, root.getRight());
        Assert.assertNull(selected.getParent());
    }

    @Test
    public void cursorRejectsEditsBelowADetachedSingularOrListAncestor() {
        for (boolean list : List.of(false, true)) {
            var first = number(1);
            var second = number(2);
            var branch = BinaryExpression.newBuilder().withArguments(first, second).build();
            var root = BinaryExpression.newInstance();
            if (list) root.getArguments().add(branch);
            else root.setLeft(branch);

            var trace = new ArrayList<String>();
            var cursor = VIterator.of(root, recordTraversal(trace, root), VIterator.IterationStrategy.CONTAINMENT_TREE);
            Assert.assertSame(root, cursor.next());
            Assert.assertSame(branch, cursor.next());
            Assert.assertSame(first, cursor.next());
            Assert.assertTrue(cursor.hasNext());
            var events = List.copyOf(trace);

            if (list) root.getArguments().clear();
            else root.setLeft(null);
            var attempted = number(3);
            Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.set(attempted));
            Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.add(attempted));
            Assert.assertFalse(cursor.isAddSupported());
            Assert.assertEquals(events, trace);
            Assert.assertEquals(2, branch.getArguments().size());
            Assert.assertSame(first, branch.getArguments().get(0));
            Assert.assertNull(attempted.getParent());

            // Read traversal continues in the frame already entered.
            Assert.assertSame(second, cursor.next());
            Assert.assertFalse(cursor.hasNext());
            Assert.assertEquals(List.of("enter root", "enter child", "enter child", "exit child",
                    "enter child", "exit child", "exit child", "exit root"), trace);
        }
    }

    @Test
    public void cursorRejectsADescendantEditAfterRestoringItsSingularAncestor() {
        var leaf = number(1);
        var branch = binary("+", leaf, null);
        var root = binary("*", branch, null);
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(branch, cursor.next());
        Assert.assertSame(leaf, cursor.next());

        var temporaryOwner = binary("-", branch, null);
        root.setLeft(branch);
        Assert.assertNull(temporaryOwner.getLeft());
        Assert.assertSame(branch, root.getLeft());

        Assert.assertThrows(ConcurrentModificationException.class, () -> cursor.set(number(2)));
        Assert.assertSame(leaf, branch.getLeft());
        Assert.assertSame(branch, leaf.getParent());
    }

    @Test
    public void cursorCanEditDescendantsOfARetainedOccurrenceAfterACursorInsertion() {
        var leaf = number(1);
        var branch = binary("+", leaf, null);
        var tail = number(2);
        var root = BinaryExpression.newBuilder().withArguments(branch, number(9), tail).build();
        root.getArguments().set(1, branch);
        var cursor = root.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        Assert.assertSame(root, cursor.next());
        Assert.assertSame(branch, cursor.next());
        Assert.assertTrue(cursor.hasNext());
        cursor.set(number(3));
        cursor.add(number(4));

        Assert.assertSame(branch, root.getArguments().get(2));
        Assert.assertSame(leaf, cursor.next());
        var replacement = number(5);
        cursor.set(replacement);
        Assert.assertSame(replacement, branch.getLeft());
        Assert.assertSame(branch, cursor.next());
        Assert.assertSame(tail, cursor.next());
        Assert.assertFalse(cursor.hasNext());
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
