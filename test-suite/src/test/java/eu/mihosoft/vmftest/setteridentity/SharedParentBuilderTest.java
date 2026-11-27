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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SharedParentBuilderTest {
    @Test public void copyingAnAttachedChildImportsItsWritableValueAndKeepsTheOwners() {
        var sourceOwner = BinaryExpression.newInstance();
        var targetOwner = BinaryExpression.newInstance();
        var source = NumberExpression.newBuilder().withValue(12.5).build();
        var target = NumberExpression.newBuilder().withValue(7.25).build();
        sourceOwner.setLeft(source);
        targetOwner.getArguments().add(target);
        var sourceView = source.asReadOnly();
        var targetView = target.asReadOnly();
        var events = new ArrayList<String>();
        sourceOwner.vmf().changes().addListener(change -> events.add("source." + change.propertyName()), false);
        targetOwner.vmf().changes().addListener(change -> events.add("target." + change.propertyName()), false);
        target.vmf().changes().addListener(change -> events.add("member." + change.propertyName()), false);

        var staged = NumberExpression.newBuilder().applyFrom(source);
        assertTrue(events.isEmpty());
        staged.applyTo(target);
        assertEquals(List.of("member.value"), events);
        assertEquals(12.5, target.getValue(), 0.0);
        assertEquals(12.5, targetView.getValue(), 0.0);
        assertSame(source, sourceOwner.getLeft());
        assertSame(target, targetOwner.getArguments().get(0));
        assertSame(sourceOwner, source.getParent());
        assertSame(targetOwner, target.getParent());
        assertSame(sourceOwner.asReadOnly(), sourceView.getParent());
        assertSame(targetOwner.asReadOnly(), targetView.getParent());

        var built = staged.build();
        assertEquals(12.5, built.getValue(), 0.0);
        assertNull(built.getParent());
        assertNull(built.asReadOnly().getParent());
        assertEquals(List.of("member.value"), events);
    }

    @Test public void ancestorImportsDoNotStageAnOwnerOrReplaceAlreadyStagedValues() {
        var owner = BinaryExpression.newInstance();
        var child = NumberExpression.newBuilder().withValue(3).build();
        owner.setRight(child);
        Expression expression = child;
        Element element = child;
        var built = NumberExpression.newBuilder().withValue(17)
                .applyFrom(expression).applyFrom(element).build();
        assertEquals(17, built.getValue(), 0.0);
        assertNull(built.getParent());
        assertSame(owner, child.getParent());
        assertSame(child, owner.getRight());
        assertFalse(Arrays.stream(NumberExpression.class.getMethods()).anyMatch(m -> m.getName().equals("setParent")));
        assertFalse(Arrays.stream(NumberExpression.Builder.class.getMethods()).anyMatch(m -> m.getName().equals("withParent")));
    }

    @Test public void abstractOwnedChildrenUseTheirSharedInverseWhenImported() {
        for (boolean append : List.of(false, true)) {
            var number = NumberExpression.newBuilder().withValue(1).build();
            var nested = BinaryExpression.newBuilder().withOperator("nested").build();
            var left = NumberExpression.newInstance();
            var right = BinaryExpression.newInstance();
            var targetLeft = NumberExpression.newInstance();
            var targetRight = BinaryExpression.newInstance();
            var previous = NumberExpression.newInstance();
            var source = BinaryExpression.newBuilder().withOperator("source").withLeft(left).withRight(right)
                    .withArguments(number, null, nested, number).build();
            var target = BinaryExpression.newBuilder().withOperator("target").withLeft(targetLeft).withRight(targetRight)
                    .withArguments(previous).build();
            var view = target.asReadOnly();
            var events = new ArrayList<String>();
            target.vmf().changes().addListener(change -> events.add(change.propertyName()), false);
            assertEquals(Arrays.asList(null, nested, number), source.getArguments());

            var staged = BinaryExpression.newBuilder().appendCollections(append).applyFrom(source);
            assertTrue(events.isEmpty());
            assertSame(source, number.getParent());
            assertSame(source, nested.getParent());
            staged.applyTo(target);
            assertSame(targetLeft, target.getLeft());
            assertSame(targetRight, target.getRight());
            assertSame(left, source.getLeft());
            assertSame(right, source.getRight());
            assertEquals(append ? Arrays.asList(previous, null, nested, number) : Arrays.asList(null, nested, number),
                    target.getArguments());
            assertEquals(Arrays.asList((Expression) null), source.getArguments());
            assertSame(target, number.getParent());
            assertSame(target, nested.getParent());
            assertSame(append ? target : null, previous.getParent());
            assertSame(number.asReadOnly(), view.getArguments().get(append ? 3 : 2));
            assertEquals("source", target.getOperator());
            assertEquals(append ? List.of("arguments", "operator") : List.of("arguments", "arguments", "operator"), events);

            var built = staged.build();
            assertSame(left, built.getLeft());
            assertSame(right, built.getRight());
            assertNull(source.getLeft());
            assertNull(source.getRight());
            assertEquals(Arrays.asList(null, nested, number), built.getArguments());
            assertSame(built, number.getParent());
            assertSame(built, nested.getParent());
            assertEquals(append ? Arrays.asList(previous, null) : Arrays.asList((Expression) null), target.getArguments());
        }
    }

    @Test public void abstractOwningBuilderChecksCyclesBeforeChangingAnyTargetProperty() {
        var source = BinaryExpression.newInstance();
        var ancestor = BinaryExpression.newInstance();
        var previous = NumberExpression.newInstance();
        var target = BinaryExpression.newBuilder().withOperator("unchanged").withArguments(previous).build();
        ancestor.setLeft(target);
        source.getArguments().add(ancestor);
        var events = new ArrayList<String>();
        target.vmf().changes().addListener(change -> events.add(change.propertyName()), false);

        var staged = BinaryExpression.newBuilder().appendCollections(false).applyFrom(source).withOperator("forbidden");
        assertThrows(IllegalArgumentException.class, () -> staged.applyTo(target));
        assertEquals("unchanged", target.getOperator());
        assertSame(previous, target.getArguments().get(0));
        assertSame(target, previous.getParent());
        assertSame(target, ancestor.getLeft());
        assertSame(ancestor, target.getParent());
        assertSame(source, ancestor.getParent());
        assertTrue(events.isEmpty());
    }
}
