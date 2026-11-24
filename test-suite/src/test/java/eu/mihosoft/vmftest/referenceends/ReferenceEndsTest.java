package eu.mihosoft.vmftest.referenceends;

import eu.mihosoft.vmf.runtime.core.*;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReferenceEndsTest {
    private static Station station(String name) {
        return Station.newBuilder().withName(name).build();
    }

    @Test public void membershipUsesIdentityThroughReorderRenameAndReadOnlyProjection() {
        var shop = Workshop.newInstance();
        OrderedRefSet<Station> stations = shop.getStations();
        var first = station("Inspection");
        var second = station("Inspection");
        assertEquals(first, second);
        assertTrue(stations.add(first));
        assertFalse(stations.contains(second));
        assertEquals(-1, stations.indexOf(second));
        assertTrue(stations.add(second));
        List<Change> edits = new ArrayList<>();
        shop.vmf().changes().addListener(edits::add);
        assertFalse(stations.add(first));
        assertFalse(stations.addAll(List.of(first, second)));
        assertTrue(edits.isEmpty());
        assertThrows(NullPointerException.class, () -> stations.addAll(Arrays.asList(station("third"), null)));
        assertEquals(2, stations.size());
        assertTrue(edits.isEmpty());
        var readOnly = shop.asReadOnly().getStations();
        assertTrue(readOnly.contains(second.asReadOnly()));
        assertTrue(stations.contains(second.asReadOnly()));
        assertSame(second.asReadOnly(), readOnly.get(1));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.remove(first.asReadOnly()));
        assertThrows(UnsupportedOperationException.class, () -> readOnly.move(0,1));
        assertThrows(UnsupportedOperationException.class, () -> {
            var it = readOnly.iterator();
            it.next();
            it.remove();
        });
        stations.move(1, 0);
        assertSame(second, stations.get(0));
        assertSame(shop, second.getWorkshop());
        assertEquals(1, edits.size());
        assertEquals("stations", edits.get(0).propertyName());
        second.setName("Calibration");
        assertTrue(stations.remove(second.asReadOnly()));
        assertNull(second.getWorkshop());
        assertSame(first, stations.get(0));
        assertSame(first.asReadOnly(), readOnly.get(0));
    }

    @Test public void graphValidationCountsRealReferencesOnBothSidesOfAReparent() {
        var oldShop = Workshop.newInstance();
        var newShop = Workshop.newInstance();
        assertEquals(0, oldShop.vmf().content().validate().get(0).getActualCount());
        var a = station("one");
        oldShop.getStations().add(a);
        assertTrue(oldShop.vmf().content().validate().isEmpty());
        a.setWorkshop(newShop);
        assertTrue(oldShop.getStations().isEmpty());
        assertEquals(0, oldShop.vmf().content().validate().get(0).getActualCount());
        assertSame(a, newShop.getStations().get(0));
        newShop.getStations().addAll(List.of(station("two"), station("three")));
        var issues = newShop.asReadOnly().vmf().content().validate();
        assertEquals(1, issues.size());
        assertEquals(3, issues.get(0).getActualCount());
        assertEquals("stations", issues.get(0).getProperty().getName());
        a.setName(null);
        assertTrue(newShop.vmf().content().validate().stream().anyMatch(issue ->
                issue.getObject() == a && issue.getProperty().getName().equals("name")));
    }

    @Test public void reflectionReportsThePublicCollectionAndReplacesExactly() {
        var shop = Workshop.newInstance();
        var property = shop.vmf().reflect().propertyByName("stations").orElseThrow();
        assertTrue(property.getType().isReferenceSetType());
        assertTrue(property.getType().isModelType());
        assertFalse(property.getType().isListType());
        assertEquals(OrderedRefSet.class.getName(), property.getType().getDescriptor().getName());
        assertEquals(Station.class.getName(), property.getType().getElementTypeName().orElseThrow());
        assertEquals(Multiplicity.of(1,2,true,true), property.getMultiplicity());
        assertSame(shop.getStations(), property.get());
        var a = station("a");
        var b = station("b");
        property.set(List.of(a, b));
        assertThrows(IllegalArgumentException.class, () -> property.set(List.of(b, b)));
        assertSame(a, shop.getStations().get(0));
        assertSame(b, shop.getStations().get(1));
        property.set(shop.getStations());
        assertSame(b, shop.getStations().get(1));
        property.unset();
        assertTrue(shop.getStations().isEmpty());
        assertNull(a.getWorkshop());
    }

    @Test public void clonePreservesAliasesBetweenContainmentAndUnorderedReferences() {
        Station a = station("a"), b = station("b");
        var shop = Workshop.newBuilder().withStations(a,b).withFavorites(b,a).build();
        var copy = shop.clone();
        assertNotSame(a, copy.getStations().get(0));
        assertTrue(copy.getFavorites().contains(copy.getStations().get(0)));
        assertTrue(copy.getFavorites().contains(copy.getStations().get(1)));
        assertFalse(copy.getFavorites().contains(a));
        assertSame(copy, copy.getStations().get(1).getWorkshop());
        assertSame(shop, a.getWorkshop());
        assertEquals(2, copy.vmf().content().stream(Station.class).count());
    }

    @Test public void unorderedContentsCompareIndependentlyOfIterationOrder() {
        Station a = station("a"), b = station("b");
        var first = Network.newBuilder().withNearby(a,b).build();
        var second = Network.newBuilder().withNearby(station("b"),station("a")).build();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        var third = Network.newBuilder().withNearby(station("a"),station("a")).build();
        assertNotEquals(first, third);
        assertTrue(first.getNearby().contains(a));
        assertFalse(first.getNearby().contains(station("a")));
        assertEquals(2, third.getNearby().size());
    }

    @Test public void anOppositeSequenceCanRepeatAnEdgeWhileTheSetHoldsOneMember() {
        var team = Team.newInstance();
        var member = Member.newInstance();
        member.getTeams().addAll(List.of(team, team));
        assertEquals(2, member.getTeams().size());
        assertEquals(1, team.getMembers().size());
        assertTrue(team.getMembers().contains(member));
        member.getTeams().remove(0);
        assertTrue(team.getMembers().contains(member));
        member.getTeams().add(team);
        team.getMembers().remove(member);
        assertTrue(member.getTeams().isEmpty());
        assertTrue(team.getMembers().isEmpty());
    }

    @Test public void twoUniqueOppositesAcceptEditsFromEitherEnd() {
        var panel = Panel.newInstance();
        var link = StationLink.newInstance();
        assertTrue(link.getPanels().add(panel));
        assertSame(link, panel.getLinks().get(0));
        assertFalse(panel.getLinks().add(link));
        assertEquals(1, link.getPanels().size());
        assertTrue(panel.getLinks().remove(link));
        assertTrue(link.getPanels().isEmpty());
    }

    @Test public void diffAndMergePreserveTheTargetOfAReferenceToAContainedStation() {
        var original = station("A");
        var source = Workshop.newBuilder().withStations(original).build();
        var desired = source.clone();
        var added = station("B");
        desired.getStations().add(added);
        desired.getFavorites().add(added);
        var changes = ModelDiff.diff(source, desired);
        ModelDiff.apply(source, changes);
        var copied = source.getStations().get(1);
        assertSame(original, source.getStations().get(0));
        assertNotSame(added, copied);
        assertTrue(source.getFavorites().contains(copied));
        assertSame(desired, added.getWorkshop());
        for (int i = changes.size() - 1; i >= 0; i--) {
            assertTrue(changes.get(i).isUndoable());
            changes.get(i).undo();
        }
        assertEquals(1, source.getStations().size());
        assertTrue(source.getFavorites().isEmpty());
        var merged = ModelDiff.merge(Workshop.newInstance(), desired);
        assertTrue(merged.getFavorites().contains(merged.getStations().get(1)));
        assertSame(desired, added.getWorkshop());
    }

    @Test public void referencesToLegacyContainmentKeepTheirAliasesDuringDiffAndMerge() {
        var desired = MixedGroup.newInstance();
        var touched = station("Inspection");
        desired.getMembers().add(touched);
        desired.getFavorites().add(touched);
        var source = MixedGroup.newInstance();
        var diff = ModelDiff.diff(source, desired);
        assertTrue(source.getMembers().isEmpty());
        ModelDiff.apply(source, diff);
        assertTrue(source.getFavorites().contains(source.getMembers().get(0)));
        assertNotSame(touched, source.getMembers().get(0));
        var merged = ModelDiff.merge(MixedGroup.newInstance(), desired);
        assertTrue(merged.getFavorites().contains(merged.getMembers().get(0)));
        assertSame(touched, desired.getMembers().get(0));
    }
}
