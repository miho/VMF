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
package eu.mihosoft.vmftest.cross_ref;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ReciprocalBuilderTest {
    @Test public void scalarImportStagesTheReferenceAndApplyUsesTheExistingTakeover() {
        var source = EntityOneA.newInstance();
        var target = EntityOneA.newInstance();
        var selected = EntityTwoA.newInstance();
        var displaced = EntityTwoA.newInstance();
        source.setRef(selected);
        target.setRef(displaced);
        var trace = new ArrayList<String>();
        source.vmf().changes().addPropertyListener("ref", change -> trace.add("previous owner"));
        displaced.vmf().changes().addPropertyListener("ref", change -> trace.add("old inverse"));
        target.vmf().changes().addPropertyListener("ref", change -> trace.add("destination"));
        selected.vmf().changes().addPropertyListener("ref", change -> trace.add("new inverse"));

        var staged = EntityOneA.newBuilder().applyFrom(source);
        assertSame(source, selected.getRef());
        assertTrue(trace.isEmpty());
        staged.applyTo(source);
        assertTrue(trace.isEmpty());
        staged.applyTo(target);
        assertEquals(List.of("previous owner", "old inverse", "destination", "new inverse"), trace);
        assertNull(source.getRef());
        assertNull(displaced.getRef());
        assertSame(selected, target.getRef());
        assertSame(target, selected.getRef());

        var built = staged.build();
        assertNull(target.getRef());
        assertSame(selected, built.getRef());
        assertSame(built, selected.getRef());
        EntityOneA.newBuilder().withRef(EntityTwoA.newBuilder()).applyTo(built);
        assertNull(built.getRef());
        assertNull(selected.getRef());
    }

    @Test public void interfaceDeclaredEndsCanBeImportedFromTheirAbstractRoles() {
        Selector source = Panel.newBuilder().withName("source").build();
        var target = Panel.newBuilder().withName("target").build();
        var selected = Button.newBuilder().withName("selected").build();
        source.setSelected(selected);
        Panel.newBuilder().applyFrom(source).applyTo(target);
        assertNull(source.getSelected());
        assertSame(selected, target.getSelected());
        assertSame(target, selected.getSelectedBy());
        assertNull(target.getName());

        Selectable previouslySelected = selected;
        var replacement = Button.newInstance();
        Button.newBuilder().applyFrom(previouslySelected).applyTo(replacement);
        assertNull(selected.getSelectedBy());
        assertSame(target, replacement.getSelectedBy());
        assertSame(replacement, target.getSelected());
    }

    @Test public void inheritedAndSelfOppositeScalarSlotsKeepTheirIdentity() {
        var owner = EntityOneA.newInstance();
        var selected = EntityTwoA.newInstance();
        owner.setRef(selected);
        var derived = SpecializedEntityOneA.newBuilder().withLabel("derived").applyFrom(owner).build();
        assertNull(owner.getRef());
        assertSame(derived, selected.getRef());
        assertSame(selected, derived.getRef());
        assertEquals("derived", derived.getLabel());

        var self = Partner.newInstance();
        var destination = Partner.newInstance();
        self.setPartner(self);
        Partner.newBuilder().applyFrom(self).applyTo(destination);
        assertSame(self, destination.getPartner());
        assertSame(destination, self.getPartner());
    }

    @Test public void ordinaryReciprocalListImportsRespectModesAndKeepTheirObserverOrder() {
        for (boolean append : List.of(false, true)) {
            var one = member("one");
            var two = member("two");
            var existing = member("existing");
            var source = EntityTwoB.newBuilder().withRefs(one, null, two, one).build();
            var target = EntityTwoB.newBuilder().withRefs(existing).build();
            var sourceRefs = source.getRefs();
            var targetRefs = target.getRefs();
            assertEquals(Arrays.asList(null, two, one), sourceRefs);
            var trace = new ArrayList<String>();
            source.vmf().changes().addPropertyListener("refs", change -> trace.add("previous"));
            target.vmf().changes().addPropertyListener("refs", change -> trace.add("destination"));
            one.vmf().changes().addPropertyListener("ref", change -> trace.add("one"));
            two.vmf().changes().addPropertyListener("ref", change -> trace.add("two"));
            existing.vmf().changes().addPropertyListener("ref", change -> trace.add("existing"));

            var staged = EntityTwoB.newBuilder().appendCollections(append).applyFrom(source);
            assertTrue(trace.isEmpty());
            assertSame(source, one.getRef());
            assertSame(source, two.getRef());
            assertSame(target, existing.getRef());
            staged.applyTo(target);
            assertSame(sourceRefs, source.getRefs());
            assertSame(targetRefs, target.getRefs());
            assertEquals(Arrays.asList((EntityOneB) null), sourceRefs);
            assertEquals(append ? Arrays.asList(existing, null, two, one) : Arrays.asList(null, two, one), targetRefs);
            assertSame(target, one.getRef());
            assertSame(target, two.getRef());
            assertSame(append ? target : null, existing.getRef());
            assertEquals(append ? List.of("previous", "two", "previous", "one", "destination")
                    : List.of("existing", "destination", "previous", "two", "previous", "one", "destination"), trace);

            trace.clear();
            var fromTarget = EntityTwoB.newBuilder().appendCollections(append).applyFrom(target);
            assertTrue(trace.isEmpty());
            fromTarget.applyTo(target);
            assertEquals(append ? Arrays.asList(null, existing, null, two, one) : Arrays.asList(null, two, one), targetRefs);
            assertEquals(append ? List.of("destination", "destination", "destination", "destination")
                    : List.of("two", "one", "destination", "two", "one", "destination"), trace);
            assertSame(target, one.getRef());
            assertSame(target, two.getRef());
        }
    }

    @Test public void buildingAnImportedReciprocalListTransfersExactlyTheExistingMembers() {
        var first = member("first");
        var second = member("second");
        var source = EntityTwoB.newBuilder().withRefs(first, null, second, first).build();
        var staged = EntityTwoB.newBuilder().applyFrom(source);
        assertSame(source, first.getRef());
        assertSame(source, second.getRef());
        var built = staged.build();
        assertEquals(Arrays.asList((EntityOneB) null), source.getRefs());
        assertEquals(Arrays.asList(null, second, first), built.getRefs());
        assertSame(built, first.getRef());
        assertSame(built, second.getRef());
    }

    @Test public void singularListInverseImportKeepsTheSourceAndMovesOnlyTheTargetMember() {
        var source = member("source");
        var target = member("target");
        var sibling = member("sibling");
        var kept = member("kept");
        var parent = EntityTwoB.newBuilder().withRefs(source, sibling).build();
        var previous = EntityTwoB.newBuilder().withRefs(target, kept).build();
        var trace = new ArrayList<String>();
        previous.vmf().changes().addPropertyListener("refs", change -> trace.add("old list"));
        parent.vmf().changes().addPropertyListener("refs", change -> trace.add("new list"));
        target.vmf().changes().addPropertyListener("ref", change -> trace.add(
                change.propertyChange().orElseThrow().newValue() == null ? "clear" : "assign"));

        var staged = EntityOneB.newBuilder().applyFrom(source);
        assertTrue(trace.isEmpty());
        assertSame(previous, target.getRef());
        staged.applyTo(source);
        assertTrue(trace.isEmpty());
        staged.applyTo(target);
        assertEquals(List.of("clear", "old list", "assign", "new list"), trace);
        assertEquals(List.of(kept), previous.getRefs());
        assertEquals(List.of(source, sibling, target), parent.getRefs());
        assertSame(parent, source.getRef());
        assertSame(parent, target.getRef());
        assertEquals("source", target.getId());

        trace.clear();
        var built = staged.build();
        assertEquals(List.of("new list"), trace);
        assertEquals(List.of(source, sibling, target, built), parent.getRefs());
        assertSame(parent, built.getRef());
    }

    private static EntityOneB member(String id) {
        return EntityOneB.newBuilder().withId(id).build();
    }
}
