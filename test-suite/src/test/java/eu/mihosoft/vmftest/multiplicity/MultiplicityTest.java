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
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmftest.multiplicity;

import eu.mihosoft.vcollections.VSetChangeEvent;
import eu.mihosoft.vmf.runtime.core.Multiplicity;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class MultiplicityTest {
    @Test public void setsPublishMembershipAndIgnoreOrder() {
        var first = ValueGroups.newBuilder().withTags("a","b","a").build();
        var second = ValueGroups.newBuilder().withTags("b","a").build();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());

        var tags = first.getTags();
        var view = first.asReadOnly().getTags();
        var changes = new ArrayList<VSetChangeEvent<String>>();
        view.addChangeListener(changes::add);
        assertFalse(tags.add("a"));
        assertFalse(tags.remove("missing"));
        assertFalse(tags.replaceContents(List.of("b","a")));
        assertTrue(changes.isEmpty());
        tags.replaceContents(List.of("b","c"));
        assertEquals(1, changes.size());
        assertSame(view, changes.get(0).source());
        assertEquals(Set.of("a"), changes.get(0).removed());
        assertEquals(Set.of("c"), changes.get(0).added());
        assertThrows(UnsupportedOperationException.class, () -> changes.get(0).added().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.removeIf(s -> false));
        assertThrows(IllegalArgumentException.class, () -> tags.addAll(List.of("x","y")));
        assertEquals(Set.of("b","c"), tags);
        assertEquals(1, changes.size());

        var iterator = tags.iterator();
        String removed = iterator.next();
        iterator.remove();
        assertEquals(Set.of(removed), changes.get(1).removed());
        assertThrows(NullPointerException.class, () -> tags.add(null));
    }

    @Test public void orderedSetAdditionsFilterDuplicatesBeforeCheckingCapacity() {
        var stages = ValueGroups.newBuilder().withStages("a").build().getStages();
        var changes = new ArrayList<List<String>>();
        stages.addChangeListener(e -> changes.add(List.copyOf(e.added().elements())));
        assertTrue(stages.addAll(0, List.of("b", "a", "b", "c", "a")));
        assertEquals(List.of("b", "c", "a"), stages);
        assertEquals(List.of(List.of("b", "c")), changes);
        var range = stages.subList(0, 2);
        assertFalse(stages.add("a"));
        assertFalse(range.add("a"));
        assertFalse(range.addAll(1, List.of("a", "c")));
        assertFalse(stages.addAll(stages));
        assertThrows(IllegalArgumentException.class, () -> stages.add(1, "a"));
        assertThrows(IllegalArgumentException.class, () -> range.add(1, "a"));
        assertThrows(IllegalArgumentException.class, () -> range.listIterator().add("a"));
        assertThrows(IllegalArgumentException.class, () -> stages.addAll(1, List.of("b", "new")));
        assertThrows(IllegalArgumentException.class, () -> stages.addAll(new int[]{1}, List.of("a")));
        assertEquals(1, changes.size());
        stages.remove("c");
        var shortRange = stages.subList(0, 1);
        assertTrue(shortRange.addAll(List.of("a", "d", "d")));
        assertEquals(List.of("b", "d"), shortRange);
        assertEquals(List.of("b", "d", "a"), stages);

        var target = ValueGroups.newBuilder().withStages("a", "b").build();
        ValueGroups.newBuilder().withStages("b", "c").appendCollections(true).applyTo(target);
        assertEquals(List.of("a", "b", "c"), target.getStages());
        target.getStages().addChangeListener(edit -> fail("Failed append must not publish a change"));
        assertThrows(IllegalArgumentException.class,
                () -> ValueGroups.newBuilder().withStages("d").applyTo(target));
        assertEquals(List.of("a", "b", "c"), target.getStages());
        assertThrows(IllegalArgumentException.class, () -> ValueGroups.newBuilder().withStages("a", "a"));
    }

    @Test public void undoOfRemovedOrderedSetMemberHonorsLaterMembership() {
        var source = ValueGroups.newBuilder().withStages("a", "b", "c").build();
        var changes = new ArrayList<eu.mihosoft.vmf.runtime.core.Change>();
        source.vmf().changes().addListener(changes::add);
        source.getStages().remove("a");
        var removal = changes.get(0);
        assertTrue(removal.isUndoable());
        source.getStages().set(0, "a");
        assertFalse(removal.isUndoable());
        removal.undo();
        assertEquals(List.of("a", "c"), source.getStages());
    }

    @Test public void orderedUniqueEditsValidateBeforeChangingAnyElement() {
        var source = ValueGroups.newBuilder().withStages("a","b","c").build();
        var list = source.getStages();
        var before = List.copyOf(list);
        assertFalse(list.add("c"));
        assertThrows(IllegalArgumentException.class, () -> list.set(0, "b"));
        assertThrows(IllegalArgumentException.class, () -> list.setAll(0, List.of("b","b")));
        assertThrows(IllegalArgumentException.class, () -> list.subList(0,2).replaceAll(x -> "x"));
        var iterator = list.listIterator();
        iterator.next();
        assertThrows(IllegalArgumentException.class, () -> iterator.set("b"));
        assertEquals(before, list);
        list.setAll(0, List.of("b","a"));
        assertEquals(List.of("b","a","c"), list);
        list.subList(0,2).sort(Comparator.naturalOrder());
        assertEquals(before, list);
        list.sort(Comparator.reverseOrder());
        assertEquals(List.of("c","b","a"), list);
        source.getSamples().addAll(List.of(2,2));
        assertThrows(IllegalArgumentException.class, () -> source.getSamples().add(3));
        assertEquals(List.of(2,2), source.getSamples());
        var projected = source.asReadOnly().getStages();
        assertSame(projected, projected.asUnmodifiable());
        projected.addChangeListener(e -> assertSame(projected, e.source()));
        list.remove(0);
        assertThrows(UnsupportedOperationException.class, () -> projected.add("x"));
    }

    @Test public void copyBuilderImmutableAndReflectionPreserveContracts() {
        var model = ValueGroups.newBuilder().withStages("one","two").withTags("xml","json").build();
        var copy = model.clone();
        copy.getTags().clear();
        copy.getStages().clear();
        assertEquals(Set.of("xml","json"), model.getTags());
        assertEquals(List.of("one","two"), model.getStages());
        var tags = model.vmf().reflect().propertyByName("tags").orElseThrow();
        assertTrue(tags.getType().isSetType());
        assertFalse(tags.getType().isListType());
        assertEquals("java.lang.String", tags.getType().getElementTypeName().orElseThrow());
        assertEquals(Multiplicity.of(0,3,false,true), tags.getMultiplicity());
        tags.set(List.of("pc"));
        assertEquals(Set.of("pc"), model.getTags());
        tags.unset();
        assertFalse(tags.isSet());
        var stages = model.vmf().reflect().propertyByName("stages").orElseThrow();
        assertEquals(Multiplicity.of(0,3,true,true), stages.getMultiplicity());
        assertTrue(stages.getType().isListType());
        stages.set(model.getStages());
        assertEquals(List.of("one","two"), model.getStages());
        assertThrows(IllegalArgumentException.class, () -> stages.set(List.of("x","x")));
        assertEquals(List.of("one","two"), model.getStages());
        assertEquals(Multiplicity.of(1,1,false,true), model.vmf().reflect().propertyByName("name").orElseThrow().getMultiplicity());
        var unused = model.vmf().reflect().propertyByName("unused").orElseThrow();
        assertEquals(Multiplicity.of(0,0,false,true), unused.getMultiplicity());
        model.setUnused("present");
        assertTrue(model.vmf().content().validate().getMultiplicityViolations().stream()
                .anyMatch(v -> v.getProperty().getName().equals("unused")));
        var fixed = FixedGroups.newBuilder().withTags("a").withStages("b").build();
        assertThrows(UnsupportedOperationException.class, () -> fixed.getTags().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> fixed.getStages().add("x"));
        var numbers = NumericGroups.newBuilder().withValues(1.0,2.0).build();
        assertEquals(Set.of(1.0,2.0), numbers.getValues());
        assertEquals("java.lang.Double", numbers.vmf().reflect().propertyByName("values").orElseThrow().getType().getElementTypeName().orElseThrow());
    }

    @Test public void movePreservesContainmentAndTheIndependentOrderOfOpposites() {
        var group = Group.newInstance();
        var other = Group.newInstance();
        var a = Member.newInstance();
        var b = Member.newInstance();
        var c = Member.newInstance();
        group.getChildren().addAll(List.of(a,b,c));
        group.getChildren().move(0,2);
        assertEquals(List.of(b,c,a), group.getChildren());
        assertSame(group, a.getParent());
        assertSame(group, b.getParent());
        group.getMembers().addAll(List.of(a,b,a));
        other.getMembers().add(a);
        var opposite = new ArrayList<>(a.getGroups());
        a.getGroups().addChangeListener(e -> fail("Reordering a list must preserve its opposite"));
        group.getMembers().move(0,1);
        assertEquals(List.of(b,a,a), group.getMembers());
        assertEquals(opposite, a.getGroups());
        var ordered = ValueGroups.newBuilder().withStages("a","b","c").build();
        ordered.getStages().move(2,0);
        assertEquals(List.of("c","a","b"), ordered.getStages());
        assertThrows(UnsupportedOperationException.class, () -> ordered.asReadOnly().getStages().move(0,1));
    }

    @Test public void redeclaredGenericGetterPreservesItsInheritedContract() {
        var sample = RedeclaredCollection.newBuilder().withNumbers(2.0).build();
        var property = sample.vmf().reflect().propertyByName("numbers").orElseThrow();
        assertEquals(Multiplicity.of(1,2,true,true), property.getMultiplicity());
        assertEquals("java.lang.Double", property.getType().getElementTypeName().orElseThrow());
        assertFalse(sample.getNumbers().add(2.0));
        assertThrows(IllegalArgumentException.class, () -> sample.getNumbers().addAll(List.of(3.0,4.0)));
        assertEquals(1, NamedGroup.newInstance().vmf().reflect().propertyByName("name").orElseThrow().getMultiplicity().getLower());
    }

    @Test public void reflectedDefaultsAreSnapshotsAndBuildersOwnTheirContents() {
        var original = DefaultGroups.newInstance();
        var tags = original.vmf().reflect().propertyByName("tags").orElseThrow();
        var stages = original.vmf().reflect().propertyByName("stages").orElseThrow();
        assertFalse(tags.isSet());
        assertFalse(stages.isSet());
        List<String> replacement = new ArrayList<>(List.of("custom"));
        tags.setDefault(replacement);
        stages.setDefault(replacement);
        replacement.clear();
        assertEquals(Set.of("custom"), original.getTags());
        assertEquals(List.of("custom"), original.getStages());
        var copy = original.clone();
        copy.getTags().add("extra");
        copy.vmf().reflect().propertyByName("tags").orElseThrow().unset();
        assertEquals(Set.of("custom"), copy.getTags());
        assertFalse(original.asReadOnly().vmf().reflect().propertyByName("stages").orElseThrow().isSet());
        var builder = DefaultGroups.newBuilder().appendCollections(false).applyFrom(original);
        var first = builder.build();
        var second = builder.withTags("y").withStages("z").build();
        assertEquals(Set.of("custom"), first.getTags());
        assertEquals(List.of("custom"), first.getStages());
        assertEquals(Set.of("y"), second.getTags());
        builder.appendCollections(true).applyTo(original);
        assertEquals(Set.of("custom","y"), original.getTags());
        assertEquals(List.of("custom","z"), original.getStages());
    }
}
