/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
 * If you use this software in scientific research please cite the following publication(s):
 *
 *  M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 *  a framework for declarative GUI programming on the Java platform.
 *  Computing and Visualization in Science, 16(4),
 *  181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.recursivelistener01;

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.ChangeListener;
import eu.mihosoft.vmf.runtime.core.Changes.Scope;
import eu.mihosoft.vmftest.diff.NodeToDiff;
import org.junit.Test;
import vjavax.observer.Subscription;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class FilteredListenerTest {
    @Test
    public void localAndRecursiveConveniencesKeepTheirObservationScopes() {
        Node root = Node.newInstance();
        Node child = Node.newInstance();
        root.getChildren().add(child);
        var changes = root.vmf().changes();
        List<Change> local = new ArrayList<>();
        List<Change> recursive = new ArrayList<>();
        var localSubscription = changes.addLocalListener(local::add);
        var recursiveSubscription = changes.addRecursiveListener(recursive::add);
        try {
            root.setName("root edit");
            child.setName("child edit");
        } finally {
            localSubscription.unsubscribe();
            recursiveSubscription.unsubscribe();
        }
        root.setName("after unsubscribe");

        assertEquals(1, local.size());
        assertSame(root, local.get(0).object());
        assertEquals(2, recursive.size());
        assertSame(root, recursive.get(0).object());
        assertSame(child, recursive.get(1).object());
    }

    @Test
    public void namedListenersMatchListAndScalarEditsAtTheirSelectedScope() {
        Node root = Node.newInstance();
        Node child = Node.newInstance();
        Node leaf = Node.newInstance();
        var changes = root.vmf().changes();
        List<Change> local = new ArrayList<>();
        List<Change> names = new ArrayList<>();
        List<Change> children = new ArrayList<>();
        var localSubscription = changes.addPropertyListener("name", local::add, Scope.LOCAL);
        var nameSubscription = changes.addPropertyListener("name", names::add, Scope.RECURSIVE);
        var childrenSubscription = changes.addPropertyListener("children", children::add, Scope.RECURSIVE);
        try {
            root.setName("root name");
            root.getChildren().add(child);
            child.setName("child name");
            child.getChildren().add(leaf);
            leaf.setName("leaf name");
            root.getChildren().remove(child);
            leaf.setName("detached leaf");
        } finally {
            localSubscription.unsubscribe();
            nameSubscription.unsubscribe();
            childrenSubscription.unsubscribe();
        }

        assertEquals(1, local.size());
        assertSame(root, local.get(0).object());
        assertEquals(3, names.size());
        assertSame(root, names.get(0).object());
        assertSame(child, names.get(1).object());
        assertSame(leaf, names.get(2).object());
        for (Change name : names) assertEquals(Change.ChangeType.PROPERTY, name.getType());
        assertEquals(3, children.size());
        assertSame(root, children.get(0).object());
        assertSame(child, children.get(1).object());
        assertSame(root, children.get(2).object());
        for (Change edit : children) assertEquals(Change.ChangeType.LIST, edit.getType());
    }

    @Test
    public void explicitFilterKeepsOtherListenersAndRecordingIndependent() {
        Node root = Node.newInstance();
        Node child = Node.newInstance();
        root.getChildren().add(child);
        var changes = root.vmf().changes();
        List<Change> delivered = new ArrayList<>();
        List<Change> lists = new ArrayList<>();
        var unfiltered = changes.addRecursiveListener(delivered::add);
        var filtered = changes.addFilteredListener(change -> change.object() == child
                && change.getType() == Change.ChangeType.LIST, lists::add, Scope.RECURSIVE);
        try {
            changes.start();
            changes.startTransaction();
            child.setName("recorded scalar");
            child.getChildren().add(Node.newInstance());
            changes.publishTransaction();
            changes.stop();

            assertEquals(2, delivered.size());
            assertEquals(1, lists.size());
            assertSame(delivered.get(1), lists.get(0));
            assertEquals(delivered, changes.transactions().get(0).changes());
        } finally {
            changes.stop();
            filtered.unsubscribe();
            unfiltered.unsubscribe();
        }
    }

    @Test
    public void unmatchedContainmentEditsStillUpdateRecursiveObservation() {
        NodeToDiff root = NodeToDiff.newInstance();
        NodeToDiff single = NodeToDiff.newInstance();
        NodeToDiff listed = NodeToDiff.newInstance();
        List<Change> delivered = new ArrayList<>();
        var listener = root.vmf().changes().addPropertyListener("name", delivered::add, Scope.RECURSIVE);
        try {
            root.setChild(single);
            root.getChildren().add(listed);
            single.setName("single child");
            listed.setName("listed child");
            root.setChild(null);
            root.getChildren().remove(listed);
            single.setName("detached single child");
            listed.setName("detached listed child");
        } finally {
            listener.unsubscribe();
        }

        assertEquals(2, delivered.size());
        assertSame(single, delivered.get(0).object());
        assertSame(listed, delivered.get(1).object());
    }

    @Test
    public void recordingStartedByOtherScopeDoesNotRecordTheTriggeringChangeTwice() {
        Node root = Node.newInstance();
        Node child = Node.newInstance();
        var changes = root.vmf().changes();
        List<String> delivery = new ArrayList<>();
        var local = changes.addPropertyListener("children", change -> delivery.add("local children"));
        var recursive = changes.addPropertyListener("name", change -> {
            var value = change.propertyChange().orElseThrow().newValue();
            delivery.add("recursive " + value);
            if ("open".equals(value)) changes.start();
        }, Scope.RECURSIVE);

        try {
            root.setName("open");
            assertTrue(changes.all().isEmpty());

            changes.startTransaction();
            root.setName("root edit");
            root.getChildren().add(child);
            child.setName("child edit");
            changes.publishTransaction();
            changes.stop();

            assertEquals(List.of("recursive open", "recursive root edit", "local children", "recursive child edit"), delivery);
            assertEquals(1, changes.transactions().size());
            var recorded = changes.transactions().get(0).changes();
            assertEquals(3, recorded.size());
            assertSame(root, recorded.get(0).object());
            assertEquals("name", recorded.get(0).propertyName());
            assertSame(root, recorded.get(1).object());
            assertEquals("children", recorded.get(1).propertyName());
            assertSame(child, recorded.get(2).object());
            assertEquals("name", recorded.get(2).propertyName());
        } finally {
            changes.stop();
            local.unsubscribe();
            recursive.unsubscribe();
        }
    }

    @Test
    public void removingOneRegistrationNeverRemovesAnEqualOrRepeatedListener() {
        var node = Node.newInstance();
        var calls = new ArrayList<String>();
        ChangeListener repeated = change -> calls.add("repeated");
        var changes = node.vmf().changes();
        var first = changes.addLocalListener(repeated);
        var middle = changes.addLocalListener(change -> calls.add("middle"));
        var last = changes.addLocalListener(repeated);
        last.unsubscribe();
        last.unsubscribe();
        node.setName("first");
        assertEquals(List.of("repeated", "middle"), calls);
        first.unsubscribe();
        middle.unsubscribe();
    }

    @Test
    public void namedDispatchPreservesReentrantSubscriptionOrder() {
        Node node = Node.newInstance();
        var changes = node.vmf().changes();
        List<String> delivery = new ArrayList<>();
        var replaced = new AtomicReference<Subscription>();
        var replacement = new AtomicReference<Subscription>();
        var first = changes.addPropertyListener("name", change -> {
            var value = change.propertyChange().orElseThrow().newValue();
            delivery.add("first " + value);
            if ("outer".equals(value)) {
                replaced.get().unsubscribe();
                replacement.set(changes.addPropertyListener("children", nested -> delivery.add("added children")));
                node.setName("nested");
                node.getChildren().add(Node.newInstance());
            }
        });
        replaced.set(changes.addPropertyListener("name", change ->
                delivery.add("removed " + change.propertyChange().orElseThrow().newValue())));

        try {
            node.setName("outer");
            node.setName("after");
        } finally {
            first.unsubscribe();
            replaced.get().unsubscribe();
            if (replacement.get() != null) replacement.get().unsubscribe();
        }
        node.setName("unsubscribed");

        assertEquals(List.of("first outer", "first nested", "added children", "removed outer", "first after"),
                delivery);
    }
}
