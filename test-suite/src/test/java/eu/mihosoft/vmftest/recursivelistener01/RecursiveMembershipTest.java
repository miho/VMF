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

import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmftest.cross_ref.EntityOneB;
import eu.mihosoft.vmftest.cross_ref.EntityTwoB;
import eu.mihosoft.vmftest.cross_ref.OneToManyGraph;
import eu.mihosoft.vmftest.setteridentity.BinaryExpression;
import eu.mihosoft.vmftest.setteridentity.NumberExpression;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class RecursiveMembershipTest {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "test.containment"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) {
            // This fixture has one member. Its editable id property is independent of the binding identity.
            if (object instanceof EntityOneB) return "member";
            if (object instanceof EntityTwoB) return ((EntityTwoB) object).getId();
            return "root";
        }
        @Override public String type(VObject object) {
            if (object instanceof EntityOneB) return "member";
            if (object instanceof EntityTwoB) return "owner";
            return "root";
        }
        @Override public VObject create(String type, String id) {
            switch (type) {
                case "member": return EntityOneB.newBuilder().withId(id).build();
                case "owner": return EntityTwoB.newBuilder().withId(id).build();
                default: return OneToManyGraph.newInstance();
            }
        }
    };

    @Test
    public void removingAnOppositeListReferenceKeepsTransientEditsOnTheContainedMember() {
        var root = OneToManyGraph.newInstance();
        var first = EntityTwoB.newBuilder().withId("first").build();
        var child = EntityOneB.newBuilder().withId("child").build();
        root.getOwners().add(first);
        root.getMembers().add(child);
        first.getRefs().add(child);
        var checkpoint = root.clone();

        try (var recorder = JournalRecorder.start(root, BINDING, "initial", 0)) {
            first.getRefs().remove(child);
            child.setId("transient");
            child.setId("child");
            var journal = recorder.finish();
            var operations = journal.transactions().get(0).operations();
            assertEquals(List.of(ChangeJournal.Kind.LIST_REMOVE, ChangeJournal.Kind.SET, ChangeJournal.Kind.SET),
                    operations.stream().map(ChangeJournal.Operation::kind).collect(Collectors.toList()));
            assertEquals("transient", operations.get(1).after().get(0).text());
            assertEquals("child", operations.get(2).after().get(0).text());
            var replayed = JournalReplayer.replay(checkpoint, BINDING, "initial", journal).getMembers().get(0);
            assertNull(replayed.getRef());
            assertEquals("child", replayed.getId());
        }
    }

    @Test
    public void ordinaryReferencesAreObservableWithoutObservingTheirTargets() {
        var root = OneToManyGraph.newInstance();
        var owner = EntityTwoB.newBuilder().withId("owner").build();
        var foreign = EntityOneB.newBuilder().withId("foreign").build();
        root.getOwners().add(owner);
        var sources = new ArrayList<VObject>();
        var observer = root.vmf().changes().addListener(change -> sources.add(change.object()), true);
        try {
            owner.getRefs().add(foreign);
            foreign.setId("still foreign");
            owner.getRefs().remove(foreign);
            assertEquals(List.of(owner, owner), sources);
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void nestedListRemovalDoesNotAttachAnAlreadyDetachedChild() {
        var root = Node.newInstance();
        var child = Node.newInstance();
        var names = new ArrayList<String>();
        var observer = root.vmf().changes().addListener(change -> {
            if (change.object() == root && change.listChange().orElseThrow().wasAdded()) {
                root.getChildren().remove(child);
            } else if (change.object() == child && "name".equals(change.propertyName())) {
                names.add(child.getName());
            }
        }, true);
        try {
            root.getChildren().add(child);
            assertNull(child.getParent());
            child.setName("detached");
            assertEquals(List.of(), names);
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void nestedListMoveAndReadditionRetainObservationExactlyOnce() {
        var root = Node.newInstance();
        var left = Node.newInstance();
        var right = Node.newInstance();
        var child = Node.newInstance();
        root.getChildren().addAll(List.of(left, right));
        left.getChildren().add(child);
        var names = new ArrayList<String>();
        var observer = root.vmf().changes().addListener(change -> {
            if (change.object() == left && change.listChange().orElseThrow().wasRemoved()) {
                right.getChildren().add(child);
            } else if (change.object() == right && change.listChange().orElseThrow().wasRemoved()) {
                right.getChildren().add(child);
            } else if (change.object() == child && "name".equals(change.propertyName())) {
                names.add(child.getName());
            }
        }, true);
        try {
            left.getChildren().remove(child);
            assertSame(right, child.getParent());
            child.setName("moved");
            right.getChildren().remove(child);
            assertSame(right, child.getParent());
            child.setName("readded");
            assertEquals(List.of("moved", "readded"), names);
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void nestedScalarMoveKeepsTheChildObservedOnlyAtItsCurrentEdge() {
        var root = BinaryExpression.newInstance();
        var child = NumberExpression.newInstance();
        var values = new ArrayList<Double>();
        var observer = root.vmf().changes().addListener(change -> {
            if (change.object() == root && "left".equals(change.propertyName())
                    && change.propertyChange().orElseThrow().newValue() == child) {
                root.setRight(child);
            } else if (change.object() == child && "value".equals(change.propertyName())) {
                values.add(child.getValue());
            }
        }, true);
        try {
            root.setLeft(child);
            assertNull(root.getLeft());
            assertSame(child, root.getRight());
            child.setValue(7);
            root.setRight(null);
            child.setValue(8);
            assertEquals(List.of(7.0), values);
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void throwingTopologyObserversDoNotLeaveMissingOrStaleSubscriptions() {
        var root = Node.newInstance();
        var child = Node.newInstance();
        var values = new ArrayList<String>();
        var failure = new IllegalStateException("observer failed");
        var observer = root.vmf().changes().addListener(change -> {
            if (change.object() == root && "children".equals(change.propertyName())) throw failure;
            if (change.object() == child && "name".equals(change.propertyName())) values.add(child.getName());
        }, true);
        try {
            assertSame(failure, assertThrows(IllegalStateException.class, () -> root.getChildren().add(child)));
            child.setName("attached");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> root.getChildren().remove(child)));
            child.setName("detached");
            assertEquals(List.of("attached"), values);
        } finally {
            observer.unsubscribe();
        }
    }

    @Test
    public void bulkReplacementAndClearTrackOnlyTheRemainingContainment() {
        var root = Node.newInstance();
        var first = Node.newInstance();
        var second = Node.newInstance();
        var third = Node.newInstance();
        var names = new ArrayList<String>();
        var observer = root.vmf().changes().addListener(change -> {
            if ("name".equals(change.propertyName())) names.add(((Node) change.object()).getName());
        }, true);
        try {
            root.getChildren().addAll(List.of(first, second));
            root.getChildren().setAll(0, List.of(second, third));
            first.setName("removed");
            second.setName("retained");
            third.setName("added");
            root.getChildren().clear();
            second.setName("cleared");
            third.setName("cleared");
            assertEquals(List.of("retained", "added"), names);
        } finally {
            observer.unsubscribe();
        }
    }
}
