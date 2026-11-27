package eu.mihosoft.vmftest.collectioncontract;

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.VIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class CollectionContractTest {
    @Test public void boundedOrdinaryBuilderTransfersSlotsAndValidatesOnlyActualMembers() {
        var first = Item.newBuilder().withName("first").build();
        var next = Item.newBuilder().withName("next").build();
        var source = Catalog.newBuilder().withItems(Arrays.asList(first, null, next)).build();
        var staged = Catalog.newBuilder().applyFrom(source);
        assertSame(source, first.getCatalog());
        assertSame(source, next.getCatalog());
        assertEquals(3, source.getItems().size());

        var target = Catalog.newInstance();
        var items = target.getItems();
        staged.appendCollections(false).applyTo(target);
        assertSame(items, target.getItems());
        assertEquals(3, items.size());
        assertSame(first, items.get(0));
        assertNull(items.get(1));
        assertSame(next, items.get(2));
        assertSame(target, first.getCatalog());
        assertSame(target, next.getCatalog());
        assertEquals(1, source.getItems().size());
        assertNull(source.getItems().get(0));
        assertTrue(target.vmf().content().validate().isValid());

        Catalog.newBuilder().withItems(Item.newBuilder().withName("extra").build()).applyTo(target);
        assertEquals(4, items.size());
        assertEquals("/items", target.vmf().content().validate().getIssues().get(0).getPath());
    }

    @Test public void undoOfARemovalRespectsMembersAddedSinceTheChange() {
        Catalog catalog = Catalog.newInstance();
        var map = catalog.getLabels();
        map.put(1, "first");
        map.put(2, "second");
        List<Change> changes = new ArrayList<>();
        catalog.vmf().changes().addListener(changes::add);
        map.remove(2);
        Change removal = changes.get(0);
        assertTrue(removal.isUndoable());
        map.put(3, "third");
        assertFalse(removal.isUndoable());
        removal.undo();
        assertEquals(Map.of(1, "first", 3, "third"), map);
        Catalog target = Catalog.newBuilder().withLabels(Map.of(4, "fourth", 5, "fifth")).build();
        Change insertion = changes.get(1);
        assertThrows(IllegalArgumentException.class, () -> insertion.apply(target));
        assertEquals(Map.of(4, "fourth", 5, "fifth"), target.getLabels());
    }

    @Test public void equalElementsDetachTheActualChildAndOppositeEndpoint() {
        Catalog catalog = Catalog.newInstance();
        Item first = Item.newBuilder().withName("same").build();
        Item second = Item.newBuilder().withName("same").build();
        assertEquals(first, second);
        catalog.getItems().addAll(List.of(first, second));
        assertTrue(catalog.getItems().remove(second));
        assertNull(first.getCatalog());
        assertSame(second, catalog.getItems().get(0));
        assertSame(catalog, second.getCatalog());

        Member a = Member.newBuilder().withName("same").build();
        Member b = Member.newBuilder().withName("same").build();
        Team team = Team.newInstance();
        a.getTeams().add(team);
        b.getTeams().add(team);
        assertEquals(a, b);
        b.getTeams().remove(team);
        assertSame(a, team.getMembers().get(0));
        assertEquals(1, team.getMembers().size());
    }

    @Test public void legacyModelListPreservesNullThroughItsReadOnlyProjection() {
        Team team = Team.newInstance();
        team.getMembers().add(null);
        assertNull(team.asReadOnly().getMembers().get(0));
    }

    @Test public void boundsDescribeTheFinishedContainmentTree() {
        Catalog catalog = Catalog.newInstance();
        var missing = catalog.vmf().content().validate().getMultiplicityViolations();
        assertEquals(1, missing.size());
        assertSame(catalog, missing.get(0).getObject());
        assertEquals("items", missing.get(0).getProperty().getName());
        assertEquals(0, missing.get(0).getActualCount());

        Item item = Item.newInstance();
        catalog.getItems().add(item);
        var childIssue = catalog.vmf().content().validate().getMultiplicityViolations();
        assertEquals(1, childIssue.size());
        assertSame(item, childIssue.get(0).getObject());
        assertTrue(childIssue.get(0).toString().contains("Item.name"));
        item.setName("one");
        assertTrue(catalog.asReadOnly().vmf().content().validate().isValid());

        for (int i = 2; i <= 3; i++) {
            Item another = Item.newInstance();
            another.setName("item " + i);
            another.setCatalog(catalog);
        }
        var overfull = catalog.vmf().content().validate().getMultiplicityViolations();
        assertEquals(1, overfull.size());
        assertEquals(3, overfull.get(0).getActualCount());
        assertEquals(2, overfull.get(0).getProperty().getMultiplicity().getUpper());
    }

    @Test public void legacyNullSlotsNeverSatisfyRelationshipBounds() {
        Catalog catalog = Catalog.newInstance();
        catalog.getItems().add(null);
        assertEquals(0, catalog.vmf().content().validate().getMultiplicityViolations().get(0).getActualCount());
        Item item = Item.newInstance();
        catalog.getItems().add(item);
        var issues = catalog.vmf().content().validate().getMultiplicityViolations();
        assertEquals(1, issues.size());
        assertSame(item, issues.get(0).getObject());
        item.setName("Inspection");
        catalog.getItems().add(null);
        assertTrue(catalog.asReadOnly().vmf().content().validate().isValid());
    }

    @Test public void validationAndIterationContinuePastANullChild() {
        Catalog catalog = Catalog.newInstance();
        Item first = Item.newBuilder().withName("first").build();
        Item last = Item.newInstance();
        catalog.getItems().add(first);
        catalog.getItems().add(null);
        catalog.getItems().add(last);

        var issues = catalog.vmf().content().validate().getMultiplicityViolations();
        assertEquals(1, issues.size());
        assertSame(last, issues.get(0).getObject());
        assertEquals("name", issues.get(0).getProperty().getName());

        var iterator = catalog.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        assertSame(catalog, iterator.next());
        assertSame(first, iterator.next());
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasNext());
        Item replacement = Item.newBuilder().withName("replacement").build();
        iterator.set(replacement);
        assertSame(replacement, catalog.getItems().get(0));
        assertNull(catalog.getItems().get(1));
        assertSame(last, iterator.next());
        assertFalse(iterator.hasNext());
    }

    @Test public void iteratorRejectsReorderingAnOwnedIdentityBeforeItMutatesTheList() {
        var catalog = Catalog.newInstance();
        var first = Item.newBuilder().withName("same").build();
        var second = Item.newBuilder().withName("second").build();
        var last = Item.newBuilder().withName("last").build();
        catalog.getItems().addAll(java.util.Arrays.asList(first, second, null, last));
        var iterator = cursorAtSecond(catalog, first, second);

        var changes = new ArrayList<Change>();
        var listener = catalog.vmf().changes().addLocalListener(changes::add);
        try {
            assertThrows(IllegalArgumentException.class, () -> iterator.add(first));
            assertSame(first, catalog.getItems().get(0));
            assertSame(second, catalog.getItems().get(1));
            assertNull(catalog.getItems().get(2));
            assertSame(last, catalog.getItems().get(3));
            assertTrue(changes.isEmpty());
            assertSame(catalog, first.getCatalog());

            var fresh = Item.newBuilder().withName("same").build();
            assertEquals(first, fresh);
            assertNotSame(first, fresh);
            iterator.add(fresh);
            assertSame(fresh, catalog.getItems().get(2));
            assertSame(catalog, fresh.getCatalog());
            assertSame(last, iterator.next());
            assertFalse(iterator.hasNext());

            var other = Catalog.newInstance();
            var transfer = Item.newBuilder().withName("transfer").build();
            other.getItems().add(transfer);
            var transferCursor = cursorAtSecond(catalog, first, second);
            transferCursor.add(transfer);
            assertTrue(other.getItems().isEmpty());
            assertSame(catalog, transfer.getCatalog());
            assertSame(transfer, catalog.getItems().get(2));
            assertSame(fresh, transferCursor.next());
            assertSame(last, transferCursor.next());
            assertFalse(transferCursor.hasNext());
        } finally {
            listener.unsubscribe();
        }
    }

    private static VIterator cursorAtSecond(Catalog catalog, Item first, Item second) {
        var iterator = catalog.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        assertSame(catalog, iterator.next());
        assertSame(first, iterator.next());
        assertSame(second, iterator.next());
        assertTrue(iterator.hasNext());
        assertTrue(iterator.hasNext());
        assertTrue(iterator.isAddSupported());
        return iterator;
    }

    @Test public void nativeSetChangesHaveMembershipSemanticsAndReadOnlySources() {
        Catalog catalog = Catalog.newInstance();
        var readOnly = catalog.asReadOnly();
        List<Change> changes = new ArrayList<>();
        List<Change> viewChanges = new ArrayList<>();
        catalog.vmf().changes().addListener(changes::add);
        readOnly.vmf().changes().addListener(viewChanges::add);
        catalog.getTags().addAll(List.of("meter", "relay"));
        assertFalse(catalog.getTags().add("relay"));
        assertFalse(catalog.getTags().remove("absent"));
        assertEquals(1, changes.size());
        assertEquals(1, viewChanges.size());
        Change edit = changes.get(0);
        assertEquals(Change.ChangeType.SET, edit.getType());
        assertEquals(Set.of("meter", "relay"), edit.setChange().orElseThrow().added());
        assertTrue(edit.setChange().orElseThrow().removed().isEmpty());
        assertSame(readOnly.getTags(), viewChanges.get(0).setChange().orElseThrow().source());
        assertFalse(viewChanges.get(0).isUndoable());
        Catalog target = Catalog.newInstance();
        edit.apply(target);
        assertEquals(catalog.getTags(), target.getTags());
        assertTrue(edit.isUndoable());
        edit.undo();
        assertTrue(catalog.getTags().isEmpty());
        assertFalse(edit.isUndoable());
    }
}
