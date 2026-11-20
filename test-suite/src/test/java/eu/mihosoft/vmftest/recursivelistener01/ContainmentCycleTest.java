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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * a framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.recursivelistener01;

import eu.mihosoft.vmftest.setteridentity.BinaryExpression;
import eu.mihosoft.vmftest.setteridentity.NumberExpression;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContainmentCycleTest {
    @Test
    public void listAndContainerEntryPointsRejectCyclesBeforeMutationOrNotification() {
        var root = Node.newInstance();
        var branch = Node.newInstance();
        var first = Node.newInstance();
        var second = Node.newInstance();
        var detached = Node.newInstance();
        root.getChildren().add(branch);
        var children = branch.getChildren();
        children.addAll(List.of(first, second));
        var events = new ArrayList<String>();
        var listener = children.addChangeListener(event -> events.add("list"));
        var changes = root.vmf().changes();
        changes.start();
        try {
            var iterator = children.listIterator();
            iterator.next();
            @SuppressWarnings("unchecked")
            var reflected = (List<Node>) branch.vmf().reflect().propertyByName("children").orElseThrow().get();
            List<Runnable> cyclicEdits = List.of(
                    () -> children.add(branch),
                    () -> children.add(0, root),
                    () -> children.set(0, root),
                    () -> children.addAll(List.of(detached, root)),
                    () -> children.addAll(0, List.of(detached, root)),
                    () -> children.addAll(new int[]{0, 1}, List.of(detached, root)),
                    () -> children.setAll(0, List.of(detached, root)),
                    () -> children.replaceAll(child -> child == first ? detached : root),
                    () -> iterator.add(root),
                    () -> iterator.set(root),
                    () -> children.subList(0, 2).replaceAll(child -> child == first ? detached : root),
                    () -> reflected.addAll(List.of(detached, root)),
                    () -> root.setParent(first),
                    () -> branch.setParent(branch));
            for (Runnable edit : cyclicEdits) {
                var error = assertThrows(IllegalArgumentException.class, edit::run);
                assertTrue(error.getMessage().contains("cycle"));
                assertSame(branch, root.getChildren().get(0));
                assertSame(root, branch.getParent());
                assertNull(root.getParent());
                assertNull(detached.getParent());
                assertEquals(2, children.size());
                assertSame(first, children.get(0));
                assertSame(second, children.get(1));
                assertSame(branch, first.getParent());
                assertSame(branch, second.getParent());
                assertTrue(changes.all().isEmpty());
                assertTrue(events.isEmpty());
            }
        } finally {
            changes.stop();
            listener.unsubscribe();
        }
    }

    @Test
    public void reparentingCallbackCannotCreateACycleWithThePendingDestination() {
        var previous = Node.newInstance();
        var destination = Node.newInstance();
        var moving = Node.newInstance();
        var validNestedChild = Node.newInstance();
        previous.getChildren().add(moving);

        var callback = previous.getChildren().addChangeListener(event -> {
            if (event.wasRemoved()) {
                assertThrows(IllegalArgumentException.class, () -> moving.getChildren().add(destination));
                assertTrue(moving.getChildren().isEmpty());
                assertNull(destination.getParent());
                moving.getChildren().add(validNestedChild);
            }
        });
        try {
            destination.getChildren().add(moving);
        } finally {
            callback.unsubscribe();
        }

        assertTrue(previous.getChildren().isEmpty());
        assertSame(moving, destination.getChildren().get(0));
        assertSame(destination, moving.getParent());
        assertSame(validNestedChild, moving.getChildren().get(0));
        assertSame(moving, validNestedChild.getParent());
        assertNull(destination.getParent());
    }

    @Test
    public void callbackCanReverseAnAttachmentAfterRemovingTheCompletedEdge() {
        var parent = Node.newInstance();
        var child = Node.newInstance();
        var changes = new ArrayList<String>();
        var callback = parent.getChildren().addChangeListener(event -> {
            if (event.wasAdded()) {
                assertSame(parent, child.getParent());
                parent.getChildren().remove(child);
                child.getChildren().add(parent);
                changes.add("reversed");
            }
        });
        try {
            parent.getChildren().add(child);
        } finally {
            callback.unsubscribe();
        }

        assertEquals(List.of("reversed"), changes);
        assertTrue(parent.getChildren().isEmpty());
        assertSame(parent, child.getChildren().get(0));
        assertSame(child, parent.getParent());
        assertNull(child.getParent());
    }

    @Test
    public void scalarContainmentRejectsCyclesBeforeReplacingTheExistingChild() {
        var root = BinaryExpression.newInstance();
        var branch = BinaryExpression.newInstance();
        var child = NumberExpression.newInstance();
        root.setLeft(branch);
        branch.setLeft(child);

        var changes = root.vmf().changes();
        changes.start();
        try {
            assertThrows(IllegalArgumentException.class, () -> branch.setLeft(root));
            assertThrows(IllegalArgumentException.class, () -> branch.setLeft(branch));
            assertThrows(IllegalArgumentException.class, () -> branch.vmf().reflect()
                    .propertyByName("left").orElseThrow().set(root));
            assertSame(branch, root.getLeft());
            assertSame(root, branch.getParent());
            assertSame(child, branch.getLeft());
            assertSame(branch, child.getParent());
            assertNull(root.getParent());
            assertTrue(changes.all().isEmpty());
        } finally {
            changes.stop();
        }
    }

    @Test
    public void validListEventsKeepTheExposedSourceAndListenerOrder() {
        var parent = Node.newInstance();
        var child = Node.newInstance();
        var children = parent.getChildren();
        var delivery = new ArrayList<String>();
        children.setEventInfo("user event");
        children.addChangeListener(event -> {
            assertSame(children, event.source());
            assertSame(parent, child.getParent());
            assertEquals("user event", event.eventInfo());
            delivery.add("first");
        });
        children.asUnmodifiable().addChangeListener(event -> {
            assertSame(children, event.source());
            delivery.add("read-only view");
        });
        children.addChangeListener(event -> delivery.add("last"));

        children.add(child);

        assertEquals(List.of("first", "read-only view", "last"), delivery);
    }
}
