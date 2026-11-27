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
package eu.mihosoft.vmftest.keyed;

import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class KeyedPropertyTest {
    private static Item item(String sku) { return Item.newBuilder().withSku(sku).build(); }

    @Test public void lazyKeyedItemsBuildFreshAndApplyOnlyAfterEagerImport() {
        for (boolean append : List.of(false, true)) {
            var calls = new AtomicInteger();
            var staged = Catalog.newBuilder().appendCollections(append)
                    .withItems(List.of(countedItemBuilder("lazy", calls)));
            var existing = item("existing");
            var target = Catalog.newBuilder().withItems(existing).build();

            staged.applyTo(target);
            assertEquals(0, calls.get());
            assertEquals(append ? List.of(existing) : List.of(), target.getItems());
            assertSame(append ? target : null, ((VObjectInternal) existing)._vmf_getContainer());

            var first = staged.build();
            var second = staged.build();
            var firstChild = first.findItemsBySku("lazy").orElseThrow();
            var secondChild = second.findItemsBySku("lazy").orElseThrow();
            assertEquals(2, calls.get());
            assertNotSame(firstChild, secondChild);
            assertSame(first, ((VObjectInternal) firstChild)._vmf_getContainer());
            assertSame(second, ((VObjectInternal) secondChild)._vmf_getContainer());

            var imported = item("imported");
            var source = Catalog.newBuilder().withItems(imported).build();
            staged.applyFrom(source);
            assertSame(source, ((VObjectInternal) imported)._vmf_getContainer());
            var built = staged.build();
            assertEquals(2, calls.get());
            assertSame(imported, built.findItemsBySku("imported").orElseThrow());
            assertTrue(source.getItems().isEmpty());
            assertSame(built, ((VObjectInternal) imported)._vmf_getContainer());

            staged.applyTo(target);
            assertEquals(2, calls.get());
            assertTrue(built.getItems().isEmpty());
            assertSame(imported, target.findItemsBySku("imported").orElseThrow());
        }
    }

    @Test public void lazyDuplicateKeysAreRejectedOnBuild() {
        var calls = new AtomicInteger();
        var staged = Catalog.newBuilder().withItems(countedItemBuilder("same", calls), countedItemBuilder("same", calls));
        var existing = item("same");
        var target = Catalog.newBuilder().withItems(existing).build();
        staged.applyTo(target);
        assertEquals(0, calls.get());
        assertThrows(IllegalArgumentException.class, staged::build);
        assertEquals(2, calls.get());
        assertSame(existing, target.findItemsBySku("same").orElseThrow());
        assertSame(target, ((VObjectInternal) existing)._vmf_getContainer());
    }

    private static Item.Builder countedItemBuilder(String sku, AtomicInteger calls) {
        var builder = Item.newBuilder().withSku(sku);
        return Item.Builder.class.cast(Proxy.newProxyInstance(Item.Builder.class.getClassLoader(),
                new Class<?>[] { Item.Builder.class }, (proxy, method, arguments) -> {
                    assertEquals("build", method.getName());
                    calls.incrementAndGet();
                    return builder.build();
                }));
    }

    @Test public void builderReplacementStagesAndMovesTheOriginalKeyedChildren() {
        Item moving = item("a"), replaced = item("b");
        var assignment = Assignment.newBuilder().withKey(moving).build();
        var source = Catalog.newBuilder().withItems(moving).withAssignments(assignment).build();
        var target = Catalog.newBuilder().withItems(replaced).build();
        source.getPrices().put(1, 11L);
        target.getPrices().put(2, 22L);
        var targetItems = target.getItems();
        var targetPrices = target.getPrices();
        var events = new ArrayList<String>();
        source.vmf().changes().addListener(change ->
                change.listChange().filter(edit -> change.propertyName().equals("items")).ifPresent(edit ->
                        events.add("source:" + (edit.wasRemoved() ? "remove" : "add"))));
        target.vmf().changes().addListener(change ->
                change.listChange().filter(edit -> change.propertyName().equals("items")).ifPresent(edit ->
                        events.add("target:" + (edit.wasRemoved() ? "remove" : "add"))));

        var staged = Catalog.newBuilder().appendCollections(false).applyFrom(source);
        assertSame(source, ((VObjectInternal) moving)._vmf_getContainer());
        assertTrue(events.isEmpty());
        staged.applyTo(source);
        assertEquals(List.of("source:remove", "source:add"), events);
        assertSame(source, ((VObjectInternal) moving)._vmf_getContainer());
        events.clear();

        staged.applyTo(target);
        assertEquals(List.of("target:remove", "source:remove", "target:add"), events);
        assertSame(targetItems, target.getItems());
        assertSame(targetPrices, target.getPrices());
        assertEquals(Map.of(1, 11L), targetPrices);
        assertTrue(source.getItems().isEmpty());
        assertTrue(source.getAssignments().isEmpty());
        assertSame(moving, target.findItemsBySku("a").orElseThrow());
        assertSame(assignment, target.findAssignmentsByKey(moving).orElseThrow());
        assertSame(target, ((VObjectInternal) moving)._vmf_getContainer());
        assertNull(((VObjectInternal) replaced)._vmf_getContainer());
        assertSame(moving, assignment.getKey());
    }

    @Test public void builderDuplicateOrLostKeyDoesNotChangeTheDestinationOrSource() {
        Item existing = item("same"), conflicting = item("same"), detached = item("valid");
        var target = Catalog.newBuilder().withItems(existing).build();
        var source = Catalog.newBuilder().withItems(conflicting).build();
        var events = new ArrayList<Boolean>();
        target.getItems().addChangeListener(edit -> events.add(edit.wasRemoved()));
        assertThrows(IllegalArgumentException.class, () -> Catalog.newBuilder().applyFrom(target).applyTo(target));
        assertThrows(IllegalArgumentException.class, () -> Catalog.newBuilder().applyFrom(source).applyTo(target));
        var staged = Catalog.newBuilder().withItems(detached).appendCollections(false);
        detached.setSku(null);
        assertThrows(IllegalArgumentException.class, () -> staged.applyTo(target));

        assertTrue(events.isEmpty());
        assertSame(existing, target.findItemsBySku("same").orElseThrow());
        assertSame(conflicting, source.findItemsBySku("same").orElseThrow());
        assertSame(source, ((VObjectInternal) conflicting)._vmf_getContainer());
        assertSame(target, ((VObjectInternal) existing)._vmf_getContainer());
        assertNull(((VObjectInternal) detached)._vmf_getContainer());
    }

    @Test public void builderChecksKeyedContainmentCyclesBeforeClearingTheDestination() {
        var root = RecursiveKeyedNode.newBuilder().withKey("root").build();
        var branch = RecursiveKeyedNode.newBuilder().withKey("branch").build();
        var leaf = RecursiveKeyedNode.newBuilder().withKey("leaf").build();
        root.getChildren().add(branch);
        branch.getChildren().add(leaf);
        var events = new ArrayList<Boolean>();
        branch.getChildren().addChangeListener(edit -> events.add(edit.wasRemoved()));

        var staged = RecursiveKeyedNode.newBuilder().withChildren(root).appendCollections(false);
        assertThrows(IllegalArgumentException.class, () -> staged.applyTo(branch));
        assertTrue(events.isEmpty());
        assertSame(leaf, branch.findChildrenByKey("leaf").orElseThrow());
        assertSame(branch, ((VObjectInternal) leaf)._vmf_getContainer());
        assertSame(root, ((VObjectInternal) branch)._vmf_getContainer());
    }

    @Test public void attachedEntriesHaveUniqueEditableKeysAcrossMutableAndReadOnlyViews() {
        Item a = item("a"), b = item("b");
        var catalog = Catalog.newBuilder().withItems(a, b).build();
        assertSame(a, catalog.findItemsBySku("a").orElseThrow());
        var readOnly = catalog.asReadOnly();
        assertSame(a.asReadOnly(), readOnly.findItemsBySku("a").orElseThrow());
        var property = catalog.vmf().reflect().propertyByName("items").orElseThrow();
        assertEquals("sku", property.getKeyPropertyName().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> a.setSku("b"));
        assertThrows(IllegalArgumentException.class, () -> a.setSku(null));
        assertEquals("a", a.getSku());
        a.setSku("c");
        assertTrue(readOnly.findItemsBySku("a").isEmpty());
        assertSame(a.asReadOnly(), readOnly.findItemsBySku("c").orElseThrow());
        var siblings = catalog.getItems();
        var incoming = item("new");
        assertThrows(IllegalArgumentException.class, () -> siblings.addAll(List.of(incoming, item("b"))));
        assertThrows(IllegalArgumentException.class, () -> siblings.add(Item.newInstance()));
        assertThrows(IllegalArgumentException.class, () -> siblings.addAll(new int[]{0,1}, List.of(incoming,incoming)));
        assertThrows(IllegalArgumentException.class, () -> siblings.setAll(0, List.of(a, item("c"))));
        assertThrows(IllegalArgumentException.class, () -> siblings.subList(0,1).set(0,item("b")));
        var iterator = siblings.listIterator(); iterator.next();
        assertThrows(IllegalArgumentException.class, () -> iterator.set(b));
        assertThrows(IllegalArgumentException.class, () -> property.set(List.of(a, item("c"))));
        assertThrows(IllegalArgumentException.class, () -> Catalog.newBuilder().withItems(a, item("c")).applyTo(catalog));
        assertEquals(List.of(a,b), siblings);
        assertNull(((VObjectInternal)incoming)._vmf_getContainer());
        siblings.remove(a);
        a.setSku(null);
        assertTrue(catalog.findItemsBySku("c").isEmpty());
    }

    @Test public void referenceKeysUseIdentityAndValuesRetainTheirOwnRelationships() {
        var catalog = Catalog.newInstance();
        Item first = item("equal"), second = item("equal"), owned = item("part");
        assertEquals(first, second);
        var entry = Assignment.newBuilder().withKey(first).withParts(owned).withLinks(second).build();
        catalog.getAssignments().add(entry);
        assertSame(entry, catalog.findAssignmentsByKey(first).orElseThrow());
        assertTrue(catalog.findAssignmentsByKey(second).isEmpty());
        assertSame(entry.asReadOnly(), catalog.asReadOnly().findAssignmentsByKey(first.asReadOnly()).orElseThrow());
        assertSame(entry, ((VObjectInternal)owned)._vmf_getContainer());
        assertNull(((VObjectInternal)first)._vmf_getContainer());
        assertNull(((VObjectInternal)second)._vmf_getContainer());
        var other = Assignment.newBuilder().withKey(second).build();
        catalog.getAssignments().add(other);
        assertThrows(IllegalArgumentException.class, () -> other.setKey(first));
    }

    @Test public void clonedAndMovedEntriesResolveWithinTheirCurrentOwner() {
        var owner = Catalog.newInstance();
        var other = Catalog.newInstance();
        var item = item("id");
        owner.getItems().add(item);
        var assignment = Assignment.newBuilder().withKey(item).withLinks(item).build();
        owner.getAssignments().add(assignment);

        var copy = owner.clone();
        var copyItem = copy.findItemsBySku("id").orElseThrow();
        assertNotSame(item, copyItem);
        var readOnly = copy.asReadOnly();
        var copiedEntry = readOnly.findAssignmentsByKey(copyItem.asReadOnly()).orElseThrow();
        assertSame(copyItem.asReadOnly(), copiedEntry.getKey());
        assertSame(copiedEntry.getKey(), copiedEntry.getLinks().get(0));
        assertTrue(copy.findAssignmentsByKey(item).isEmpty());

        var occupied = item("id");
        other.getItems().add(occupied);
        assertThrows(IllegalArgumentException.class, () -> other.getItems().add(item));
        assertSame(item, owner.findItemsBySku("id").orElseThrow());
        other.getItems().clear();
        other.getItems().add(item);
        assertTrue(owner.findItemsBySku("id").isEmpty());
        assertSame(item, other.findItemsBySku("id").orElseThrow());
        assertSame(assignment, owner.findAssignmentsByKey(item).orElseThrow());
    }

    @Test public void reparentCallbacksCannotIntroduceADuplicateAtTheDestination() {
        var source = Catalog.newInstance();
        var destination = Catalog.newInstance();
        Item moving = item("moving"), taken = item("taken");
        source.getItems().add(moving);
        destination.getItems().add(taken);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        source.getItems().addChangeListener(e -> {
            if (e.wasRemoved()) {
                assertThrows(IllegalArgumentException.class, () -> moving.setSku("taken"));
                attempts.incrementAndGet();
            }
        });
        destination.getItems().add(moving);
        assertEquals(1, attempts.get());
        assertSame(moving, destination.findItemsBySku("moving").orElseThrow());
        assertSame(taken, destination.findItemsBySku("taken").orElseThrow());
    }

    @Test public void indexedMembershipAndSparseReorderingCanBeUndoneAndApplied() {
        var catalog = Catalog.newInstance();
        Item a = item("a"), b = item("b"), c = item("c");
        var items = catalog.getItems();
        var changes = new java.util.ArrayList<eu.mihosoft.vmf.runtime.core.Change>();
        catalog.vmf().changes().addListener(e -> {
            if (e.propertyName().equals("items")) changes.add(e);
        });
        items.addAll(new int[]{2, 0, 1}, List.of(a, c, b));
        assertEquals(List.of(c, b, a), items);
        items.sort(java.util.Comparator.comparing(Item::getSku));
        var sort = changes.get(changes.size() - 1);
        assertEquals(List.of(a, b, c), items);
        assertArrayEquals(new int[]{0, 2}, sort.listChange().orElseThrow().added().indices());
        sort.undo();
        assertEquals(List.of(c, b, a), items);
        sort.apply(catalog);
        assertEquals(List.of(a, b, c), items);
        items.clear();
        var clear = changes.get(changes.size() - 1);
        clear.undo();
        assertEquals(List.of(a, b, c), items);
        assertSame(c, catalog.findItemsBySku("c").orElseThrow());
        assertSame(catalog, ((VObjectInternal) c)._vmf_getContainer());
    }

    @Test public void compatibleIndependentParentsShareOneKeyedRelationship() {
        var item = item("aa");
        var warehouse = Warehouse.newBuilder().withItems(item).build();
        assertSame(item, warehouse.findItemsBySku("aa").orElseThrow());
        assertSame(warehouse, ((VObjectInternal) item)._vmf_getContainer());
        assertEquals("sku", warehouse.vmf().reflect().propertyByName("items").orElseThrow().getKeyPropertyName().orElseThrow());
        item.setSku("bb");
        assertSame(item.asReadOnly(), warehouse.asReadOnly().findItemsBySku("bb").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> warehouse.getItems().add(item("bb")));
    }

    @Test public void boundedMapsPreflightNewKeysAndAllowReplacementAtCapacity() {
        var catalog = Catalog.newBuilder().withPrices(Map.of(1, 1L, 2, 2L)).build();
        var map = catalog.getPrices();
        var property = catalog.vmf().reflect().propertyByName("prices").orElseThrow();
        var changes = new java.util.ArrayList<eu.mihosoft.vmf.runtime.core.Change>();
        catalog.vmf().changes().addListener(changes::add);
        assertThrows(IllegalArgumentException.class, () -> map.putAll(Map.of(2, 20L, 3, 30L)));
        assertThrows(IllegalArgumentException.class, () -> map.computeIfAbsent(3, k -> 30L));
        assertEquals(Map.of(1, 1L, 2, 2L), map);
        assertTrue(changes.isEmpty());
        map.putAll(Map.of(1, 10L, 2, 20L));
        property.set(Map.of(3, 30L, 4, 40L));
        assertSame(map, catalog.getPrices());
        assertEquals(Map.of(3, 30L, 4, 40L), map);
        assertThrows(IllegalArgumentException.class, () -> property.set(Map.of(1, 1L, 2, 2L, 3, 3L)));
        assertThrows(IllegalArgumentException.class, () -> property.setDefault(Map.of(1, 1L, 2, 2L, 3, 3L)));
        assertThrows(IllegalArgumentException.class, () -> Catalog.newBuilder().withPrices(Map.of(1, 1L, 2, 2L, 3, 3L)));
        var append = Catalog.newBuilder().withPrices(Map.of(4, 400L, 5, 500L)).appendCollections(true);
        assertThrows(IllegalArgumentException.class, () -> append.applyTo(catalog));
        var copy = catalog.clone();
        assertThrows(IllegalArgumentException.class, () -> copy.getPrices().put(5, 50L));
        assertEquals(Map.of(3, 30L, 4, 40L), copy.getPrices());
        map.remove(3);
        var removal = changes.get(changes.size() - 1);
        assertTrue(removal.isUndoable());
        map.put(5, 50L);
        assertFalse(removal.isUndoable());
        removal.undo();
        assertEquals(Map.of(4, 40L, 5, 50L), map);
    }

    @Test public void boundMapKeysAndImmutableModelArgumentsKeepTheirConcreteTypes() {
        var catalog = Catalog.newBuilder().withPrices(Map.of(7, 12L)).build();
        assertEquals(Long.valueOf(12), catalog.getPrices().get(7));
        assertEquals(catalog.getPrices(), catalog.clone().getPrices());
        Map raw = catalog.getPrices();
        assertThrows(ClassCastException.class, () -> raw.put("bad", 13L));
        assertThrows(NullPointerException.class, () -> raw.put(null, 13L));
        var code = Code.newBuilder().withName("A").build();
        var slot = CodeSlot.newBuilder().withValue(code).withHistory(code).build();
        Code value = slot.asReadOnly().getValue();
        assertSame(code, value);
        assertSame(code, slot.asReadOnly().getHistory().get(0));
    }

    @Test public void ancestorImportReplacesStagedLazyBuildersOfBoundModelValues() {
        var code = Code.newBuilder().withName("imported").build();
        Slot<Code> source = CodeSlot.newBuilder().withValue(code).withHistory(code).build();
        var staged = CodeSlot.newBuilder().withValue(Code.newBuilder().withName("lazy"))
                .withHistory(Code.newBuilder().withName("lazy")).applyFrom(source);
        var built = staged.build();

        assertSame(code, built.getValue());
        assertEquals(1, built.getHistory().size());
        assertSame(code, built.getHistory().get(0));

        Slot<Code> empty = CodeSlot.newInstance();
        var cleared = staged.withValue(Code.newBuilder().withName("lazy"))
                .withHistory(Code.newBuilder().withName("lazy")).applyFrom(empty).build();
        assertNull(cleared.getValue());
        assertTrue(cleared.getHistory().isEmpty());
    }
}
