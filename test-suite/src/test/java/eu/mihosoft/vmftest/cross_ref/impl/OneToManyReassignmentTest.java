/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmftest.cross_ref.impl;

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmftest.cross_ref.EntityOneB;
import eu.mihosoft.vmftest.cross_ref.EntityTwoB;
import eu.mihosoft.vmftest.cross_ref.OneToManyGraph;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OneToManyReassignmentTest {
    private static final String CHECKPOINT = "initial";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() {
            return "test.one-to-many";
        }

        @Override public int modelVersion() {
            return 1;
        }

        @Override public String id(VObject object) {
            if (object instanceof EntityOneB) return ((EntityOneB) object).getId();
            if (object instanceof EntityTwoB) return ((EntityTwoB) object).getId();
            return "root";
        }

        @Override public String type(VObject object) {
            if (object instanceof EntityOneB) return "member";
            if (object instanceof EntityTwoB) return "owner";
            return "graph";
        }

        @Override public VObject create(String type, String id) {
            throw new AssertionError("Moving between existing owners must not create objects");
        }
    };

    @Test
    public void listMoveKeepsNativeOrderAndReplaysFromFreshCheckpoint() {
        OneToManyGraph graph = checkpoint();
        var changes = graph.vmf().changes();
        var previous = graph.getOwners().get(0);
        var destination = graph.getOwners().get(1);
        var moved = graph.getMembers().get(1);
        List<String> events = new ArrayList<>();
        previous.getRefs().addChangeListener(event -> {
            assertTrue(event.wasRemoved());
            assertSame(destination, moved.getRef());
            events.add("detach");
        });
        moved.vmf().changes().addListener(change -> {
            var reference = change.propertyChange().orElseThrow();
            assertSame(previous, reference.oldValue());
            assertSame(destination, reference.newValue());
            events.add("inverse");
        }, false);
        destination.getRefs().addChangeListener(event -> {
            assertTrue(event.wasAdded());
            assertSame(destination, moved.getRef());
            events.add("attach");
        });

        ChangeJournal journal;
        try (var recorder = JournalRecorder.start(graph, BINDING, CHECKPOINT, 0)) {
            changes.startTransaction();
            destination.getRefs().add(moved);
            assertEquals(List.of("detach", "inverse", "attach"), events);
            changes.publishTransaction();
            journal = recorder.finish();
        }

        List<Change> records = List.copyOf(changes.all());
        assertEquals(2, records.size());
        assertSame(previous, records.get(0).object());
        assertSame(destination, records.get(1).object());
        assertEquals(1, journal.transactions().size());
        var operations = journal.transactions().get(0).operations();
        assertEquals(2, operations.size());
        assertEquals(ChangeJournal.Kind.LIST_REMOVE, operations.get(0).kind());
        assertEquals(List.of(1), operations.get(0).indices());
        assertEquals(ChangeJournal.Kind.LIST_ADD, operations.get(1).kind());
        assertMoved(graph);

        OneToManyGraph fresh = checkpoint();
        assertMoved(JournalReplayer.replay(fresh, BINDING, CHECKPOINT, journal));
        assertInitial(fresh);
    }

    @Test
    public void nativeUndoRestoresPreviousOwnerAndOriginalListPosition() {
        var graph = checkpoint();
        var changes = graph.vmf().changes();
        changes.start();
        changes.startTransaction();
        graph.getOwners().get(1).getRefs().add(graph.getMembers().get(1));
        changes.publishTransaction();
        changes.stop();
        List<Change> records = List.copyOf(changes.all());
        assertEquals(2, records.size());

        changes.transactions().get(0).undo();
        assertInitial(graph);
        records.forEach(change -> change.apply(change.object()));
        assertMoved(graph);
    }

    @Test
    public void removingStaleOrDuplicateOccurrencesKeepsTheCurrentOwner() {
        var graph = checkpoint();
        var previous = graph.getOwners().get(0);
        var destination = graph.getOwners().get(1);
        var member = graph.getMembers().get(1);
        destination.getRefs().add(member);

        destination.getRefs().add(member);
        assertEquals(List.of(member), destination.getRefs());
        assertSame(destination, member.getRef());

        ((EntityTwoBImpl) previous).__vmf_get_prop_refs__vmf_RawList().add(member);
        previous.getRefs().remove(2);
        assertSame(destination, member.getRef());

        ((EntityTwoBImpl) destination).__vmf_get_prop_refs__vmf_RawList().add(member);
        destination.getRefs().remove(1);
        assertSame(destination, member.getRef());
        destination.getRefs().remove(0);
        assertNull(member.getRef());
    }

    @Test
    public void throwingReciprocalAddListenerRestoresTheListMarker() {
        var owner = EntityTwoB.newInstance();
        var first = EntityOneB.newInstance();
        var second = EntityOneB.newInstance();
        var refs = owner.getRefs();
        refs.setEventInfo("application");
        var listener = refs.addChangeListener(event -> { throw new ListenerFailure(); });

        try {
            assertThrows(ListenerFailure.class, () -> first.setRef(owner));
        } finally {
            listener.unsubscribe();
        }
        assertEquals("application", refs.getEventInfo());
        assertSame(owner, first.getRef());
        refs.add(second);
        assertSame(owner, second.getRef());
    }

    private static OneToManyGraph checkpoint() {
        var graph = OneToManyGraph.newInstance();
        for (String id : List.of("before", "moved", "after")) {
            var member = EntityOneB.newInstance();
            member.setId(id);
            graph.getMembers().add(member);
        }
        for (String id : List.of("previous", "destination")) {
            var owner = EntityTwoB.newInstance();
            owner.setId(id);
            graph.getOwners().add(owner);
        }
        graph.getOwners().get(0).getRefs().addAll(graph.getMembers());
        return graph;
    }

    private static void assertInitial(OneToManyGraph graph) {
        var previous = graph.getOwners().get(0);
        assertEquals(graph.getMembers(), previous.getRefs());
        assertSame(previous, graph.getMembers().get(1).getRef());
        assertTrue(graph.getOwners().get(1).getRefs().isEmpty());
    }

    private static void assertMoved(OneToManyGraph graph) {
        var previous = graph.getOwners().get(0);
        var destination = graph.getOwners().get(1);
        var members = graph.getMembers();
        assertEquals(List.of(members.get(0), members.get(2)), previous.getRefs());
        assertEquals(List.of(members.get(1)), destination.getRefs());
        assertSame(destination, members.get(1).getRef());
    }

    private static final class ListenerFailure extends RuntimeException { }
}
