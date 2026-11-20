/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.mihosoft.vmftest.recursivelistener01;

import eu.mihosoft.vmf.runtime.core.ObservableObject;
import eu.mihosoft.vmftest.events_undo_redo.ChildSingleContainment;
import eu.mihosoft.vmftest.events_undo_redo.ParentSingleContainment;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class ContainmentObserverFailureTest {
    @Test
    public void listReparentCompletesWhenTheSourceObserverThrows() {
        var source = Node.newInstance();
        var destination = Node.newInstance();
        var child = Node.newInstance();
        source.getChildren().add(child);
        var error = new AssertionError("source removal");
        var order = new ArrayList<String>();
        source.getChildren().addChangeListener(event -> {
            assertSame(source, child.getParent());
            order.add("source removal");
            throw error;
        });
        source.getChildren().addChangeListener(event -> order.add("another source observer"));
        ((ObservableObject) child).addPropertyChangeListener(event ->
                order.add(child.getParent() == null ? "child detached" : "child attached"));
        destination.getChildren().addChangeListener(event -> {
            assertSame(destination, child.getParent());
            order.add("destination addition");
        });

        assertSame(error, assertThrows(AssertionError.class, () -> destination.getChildren().add(child)));

        assertEquals(List.of("source removal", "another source observer", "child detached",
                "child attached", "destination addition"), order);
        assertTrue(source.getChildren().isEmpty());
        assertSame(child, destination.getChildren().get(0));
        assertSame(destination, child.getParent());
    }

    @Test
    public void bulkListAttachmentCompletesAndPreservesTheFirstObserverFailure() {
        var root = Node.newInstance();
        var first = Node.newInstance();
        var second = Node.newInstance();
        var attachError = new AssertionError("first child");
        var laterError = new IllegalStateException("second child");
        var listError = new AssertionError("parent list");
        ((ObservableObject) first).addPropertyChangeListener(event -> { throw attachError; });
        ((ObservableObject) second).addPropertyChangeListener(event -> { throw laterError; });
        root.getChildren().addChangeListener(event -> { throw listError; });

        assertSame(attachError, assertThrows(AssertionError.class, () -> root.getChildren().addAll(List.of(first, second))));

        assertArrayEquals(new Throwable[] {laterError, listError}, attachError.getSuppressed());
        assertSame(first, root.getChildren().get(0));
        assertSame(second, root.getChildren().get(1));
        assertSame(root, first.getParent());
        assertSame(root, second.getParent());
    }

    @Test
    public void scalarReparentCompletesWhenTheOldOwnersBeanObserverThrows() {
        var source = ParentSingleContainment.newInstance();
        var destination = ParentSingleContainment.newInstance();
        var child = ChildSingleContainment.newInstance();
        source.setChild(child);
        var error = new IllegalStateException("old owner");
        var order = new ArrayList<String>();
        ((ObservableObject) source).addPropertyChangeListener(event -> {
            assertSame(source, child.getParent());
            order.add("source removal");
            throw error;
        });
        ((ObservableObject) child).addPropertyChangeListener(event ->
                order.add(child.getParent() == null ? "child detached" : "child attached"));
        ((ObservableObject) destination).addPropertyChangeListener(event -> order.add("destination addition"));

        assertSame(error, assertThrows(IllegalStateException.class, () -> child.setParent(destination)));

        assertEquals(List.of("source removal", "child detached", "child attached", "destination addition"), order);
        assertNull(source.getChild());
        assertSame(child, destination.getChild());
        assertSame(destination, child.getParent());
    }

    @Test
    public void directParentDetachCompletesWhenTheListObserverThrows() {
        var root = Node.newInstance();
        var child = Node.newInstance();
        root.getChildren().add(child);
        var error = new AssertionError("children observer");
        root.getChildren().addChangeListener(event -> { throw error; });

        assertSame(error, assertThrows(AssertionError.class, () -> child.setParent(null)));

        assertTrue(root.getChildren().isEmpty());
        assertNull(child.getParent());
    }

    @Test
    public void nestedPublicEditCompletesAndReportsItsOwnObserverFailure() {
        var root = Node.newInstance();
        var child = Node.newInstance();
        var nestedRoot = Node.newInstance();
        var nestedChild = Node.newInstance();
        var error = new AssertionError("nested edit");
        nestedRoot.getChildren().addChangeListener(event -> { throw error; });
        ((ObservableObject) child).addPropertyChangeListener(event -> {
            assertSame(error, assertThrows(AssertionError.class, () -> nestedRoot.getChildren().add(nestedChild)));
            assertSame(nestedRoot, nestedChild.getParent());
        });

        root.getChildren().add(child);

        assertSame(root, child.getParent());
        assertSame(child, root.getChildren().get(0));
        assertSame(nestedChild, nestedRoot.getChildren().get(0));
    }

    @Test
    public void nestedEditorDoesNotInheritTheSyntheticSourceRemoval() {
        var source = Node.newInstance();
        var destination = Node.newInstance();
        var moving = Node.newInstance();
        var added = Node.newInstance();
        var error = new AssertionError("nested list addition");
        source.getChildren().add(moving);
        source.getChildren().addChangeListener(event -> {
            if (event.wasRemoved()) {
                assertSame(error, assertThrows(AssertionError.class, () -> source.getChildren().add(added)));
            } else if (event.wasAdded()) {
                throw error;
            }
        });

        destination.getChildren().add(moving);

        assertSame(added, source.getChildren().get(0));
        assertSame(source, added.getParent());
        assertSame(moving, destination.getChildren().get(0));
        assertSame(destination, moving.getParent());
    }

    @Test
    public void nestedReattachmentOfThePendingChildIsRejectedBeforeMutation() {
        var source = Node.newInstance();
        var destination = Node.newInstance();
        var other = Node.newInstance();
        var child = Node.newInstance();
        source.getChildren().add(child);
        source.getChildren().addChangeListener(event -> {
            assertSame(source, child.getParent());
            assertThrows(IllegalArgumentException.class, () -> other.getChildren().add(child));
            assertTrue(other.getChildren().isEmpty());
        });

        destination.getChildren().add(child);

        assertTrue(source.getChildren().isEmpty());
        assertTrue(other.getChildren().isEmpty());
        assertSame(child, destination.getChildren().get(0));
        assertSame(destination, child.getParent());
    }

    @Test
    public void pendingDestinationListRejectsDestructiveWritesThroughItsViews() {
        var source = Node.newInstance();
        var destination = Node.newInstance();
        var prior = Node.newInstance();
        var child = Node.newInstance();
        source.getChildren().add(child);
        var incoming = destination.getChildren();
        incoming.add(prior);
        var retainedIterator = incoming.iterator();
        assertSame(prior, retainedIterator.next());
        source.getChildren().addChangeListener(event -> {
            assertThrows(IllegalArgumentException.class, () -> incoming.remove(child));
            assertThrows(IllegalArgumentException.class, () -> incoming.removeAll(1));
            assertThrows(IllegalArgumentException.class, () -> incoming.removeIf(candidate -> candidate == child));
            assertThrows(IllegalArgumentException.class, () -> incoming.subList(1, 2).clear());
            assertThrows(IllegalArgumentException.class, retainedIterator::remove);
            var nestedIterator = incoming.listIterator(1);
            assertSame(child, nestedIterator.next());
            assertThrows(IllegalArgumentException.class, nestedIterator::remove);
            assertThrows(IllegalArgumentException.class, () -> child.setParent(null));
            assertSame(source, child.getParent());
        });

        incoming.add(child);

        assertEquals(2, incoming.size());
        assertSame(prior, incoming.get(0));
        assertSame(child, incoming.get(1));
        assertSame(destination, child.getParent());
        assertTrue(source.getChildren().isEmpty());
        assertSame(child, incoming.remove(1));
        assertNull(child.getParent());
    }

    @Test
    public void pendingScalarSlotRejectsACompetingAssignmentAndDirectDetach() {
        var root = ParentSingleContainment.newInstance();
        var child = ChildSingleContainment.newInstance();
        var competing = ChildSingleContainment.newInstance();
        var linked = new ArrayList<ChildSingleContainment>();
        ((ObservableObject) child).addPropertyChangeListener(event -> {
            if (child.getParent() != root) return;
            assertThrows(IllegalArgumentException.class, () -> root.setChild(competing));
            assertThrows(IllegalArgumentException.class, () -> child.setParent(null));
            assertNull(root.getChild());
            assertNull(competing.getParent());
            linked.add(child);
        });

        root.setChild(child);

        assertEquals(1, linked.size());
        assertSame(child, root.getChild());
        assertSame(root, child.getParent());
        root.setChild(competing);
        assertSame(competing, root.getChild());
        assertNull(child.getParent());
    }

    @Test
    public void bulkIncomingSlotsStayProtectedUntilAllChildrenAreLinked() {
        var root = Node.newInstance();
        var first = Node.newInstance();
        var second = Node.newInstance();
        var incoming = root.getChildren();
        ((ObservableObject) first).addPropertyChangeListener(event -> {
            assertThrows(IllegalArgumentException.class, () -> incoming.subList(1, 2).clear());
            assertThrows(IllegalArgumentException.class, () -> incoming.set(1, Node.newInstance()));
            assertSame(second, incoming.get(1));
        });

        incoming.addAll(List.of(first, second));

        assertSame(first, incoming.get(0));
        assertSame(second, incoming.get(1));
        assertSame(root, first.getParent());
        assertSame(root, second.getParent());
    }

    @Test
    public void outgoingListChildCannotBeMovedOrReaddedBeforeItsParentIsCleared() {
        var source = Node.newInstance();
        var thirdOwner = Node.newInstance();
        var outgoing = Node.newInstance();
        source.getChildren().add(outgoing);
        source.getChildren().addChangeListener(event -> {
            assertSame(source, outgoing.getParent());
            assertThrows(IllegalArgumentException.class, () -> thirdOwner.getChildren().add(outgoing));
            assertThrows(IllegalArgumentException.class, () -> source.getChildren().add(outgoing));
            assertThrows(IllegalArgumentException.class, () -> outgoing.setParent(null));
            assertTrue(thirdOwner.getChildren().isEmpty());
            assertTrue(source.getChildren().isEmpty());
        });

        outgoing.setParent(null);

        assertNull(outgoing.getParent());
        assertTrue(source.getChildren().isEmpty());
        thirdOwner.getChildren().add(outgoing);
        assertSame(outgoing, thirdOwner.getChildren().get(0));
        assertSame(thirdOwner, outgoing.getParent());
    }

    @Test
    public void outgoingScalarChildCannotBeMovedDuringClearOrReplacement() {
        for (boolean replace : new boolean[] {false, true}) {
            var source = ParentSingleContainment.newInstance();
            var thirdOwner = ParentSingleContainment.newInstance();
            var outgoing = ChildSingleContainment.newInstance();
            var incoming = replace ? ChildSingleContainment.newInstance() : null;
            source.setChild(outgoing);
            ((ObservableObject) source).addPropertyChangeListener(event -> {
                if (event.getOldValue() != outgoing || event.getNewValue() != null) return;
                assertSame(source, outgoing.getParent());
                assertThrows(IllegalArgumentException.class, () -> thirdOwner.setChild(outgoing));
                assertThrows(IllegalArgumentException.class, () -> source.setChild(outgoing));
                assertThrows(IllegalArgumentException.class, () -> outgoing.setParent(null));
                assertNull(thirdOwner.getChild());
                assertNull(source.getChild());
            });

            source.setChild(incoming);

            assertSame(incoming, source.getChild());
            if (incoming != null) assertSame(source, incoming.getParent());
            assertNull(outgoing.getParent());
            thirdOwner.setChild(outgoing);
            assertSame(outgoing, thirdOwner.getChild());
            assertSame(thirdOwner, outgoing.getParent());
        }
    }

    @Test
    public void childMayMoveInItsOwnNotificationAfterTheParentWasCleared() {
        var source = Node.newInstance();
        var thirdOwner = Node.newInstance();
        var outgoing = Node.newInstance();
        source.getChildren().add(outgoing);
        ((ObservableObject) outgoing).addPropertyChangeListener(event -> {
            if (event.getNewValue() == null) thirdOwner.getChildren().add(outgoing);
        });

        outgoing.setParent(null);

        assertTrue(source.getChildren().isEmpty());
        assertSame(outgoing, thirdOwner.getChildren().get(0));
        assertSame(thirdOwner, outgoing.getParent());
    }
}
