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

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class KeyedPropertyTest {
    private static Item item(String sku) { return Item.newBuilder().withSku(sku).build(); }

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
}
