package eu.mihosoft.vmftest.referenceends;

import eu.mihosoft.vmf.runtime.core.RefSetChange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ReferenceCopyTest {
    @Test public void shallowCopiesOwnTheirMembershipAndMaintainManyValuedOpposites() {
        Station s = Station.newInstance();
        Network source = Network.newBuilder().withNearby(s).build();
        Network copy = source.vmf().content().shallowCopy();
        assertSame(s, copy.getNearby().iterator().next());
        copy.getNearby().remove(s);
        assertTrue(source.getNearby().contains(s));

        Member m = Member.newInstance();
        Team team = Team.newBuilder().withMembers(m).build();
        Team another = team.vmf().content().shallowCopy();
        assertEquals(2, m.getTeams().size());
        assertSame(team, m.getTeams().get(0));
        assertSame(another, m.getTeams().get(1));
        another.getMembers().remove(m);
        assertTrue(team.getMembers().contains(m));
        assertSame(team, m.getTeams().get(0));
    }

    @Test public void shallowCopyCanCopyEmptyContainmentAndCannotStealAnExistingOwner() {
        Workshop workshop = Workshop.newInstance();
        assertTrue(workshop.getStations().isEmpty());
        Workshop empty = workshop.vmf().content().shallowCopy();
        assertTrue(empty.getStations().isEmpty());
        Station s = Station.newInstance();
        workshop.getStations().add(s);
        assertThrows(IllegalStateException.class, () -> workshop.vmf().content().shallowCopy());
        assertSame(workshop, s.getWorkshop());

        Roster roster = Roster.newInstance();
        Registration registration = Registration.newInstance();
        registration.setRoster(roster);
        assertThrows(IllegalStateException.class, () -> roster.vmf().content().shallowCopy());
        assertSame(roster, registration.getRoster());
        assertTrue(roster.getRegistrations().contains(registration));
    }

    @Test public void declaredReferenceDefaultsKeepTheirInstanceIdentityAcrossFactoryAndBuilder() {
        for (DefaultNetwork network : List.of(DefaultNetwork.newInstance(), DefaultNetwork.newBuilder().build())) {
            var refs = network.getNearby();
            var initial = refs.iterator().next();
            var property = network.vmf().reflect().propertyByName("nearby").orElseThrow();
            assertFalse(property.isSet());
            refs.clear();
            property.unset();
            assertSame(initial, refs.iterator().next());
            DefaultNetwork copy = network.clone();
            assertFalse(copy.vmf().reflect().propertyByName("nearby").orElseThrow().isSet());
            assertNotSame(initial, copy.getNearby().iterator().next());
        }
        var immutable = LabelBook.newBuilder().build();
        assertEquals("initial", immutable.getLabels().iterator().next().getName());
        assertFalse(immutable.vmf().reflect().propertyByName("labels").orElseThrow().isSet());
        assertThrows(UnsupportedOperationException.class, () -> immutable.getLabels().clear());
    }

    @Test public void cloneMapsReferenceDefaultsThroughTheSameIdentityMapAsLiveProperties() {
        Network source = Network.newInstance();
        Station s = Station.newInstance();
        source.vmf().reflect().propertyByName("nearby").orElseThrow().setDefault(List.of(s));
        Network cloned = source.clone();
        Station clonedStation = cloned.getNearby().iterator().next();
        assertNotSame(s, clonedStation);
        var property = cloned.vmf().reflect().propertyByName("nearby").orElseThrow();
        assertFalse(property.isSet());
        cloned.getNearby().clear();
        assertTrue(property.isSet());
        property.unset();
        assertSame(clonedStation, cloned.getNearby().iterator().next());
        var readOnly = cloned.asReadOnly().vmf().reflect().propertyByName("nearby").orElseThrow();
        assertSame(clonedStation.asReadOnly(), ((List<?>) readOnly.getDefault()).get(0));
    }

    @Test public void orderedBuilderKeepsPositionsInverseLinksAndReflectedDefaults() {
        var panel = Panel.newInstance();
        var a = StationLink.newInstance(); var b = StationLink.newInstance();
        var c = StationLink.newInstance(); var d = StationLink.newInstance();
        var e = StationLink.newInstance(); var x = StationLink.newInstance(); var y = StationLink.newInstance();
        var original = List.of(a, b, c, d, e);
        var links = panel.getLinks();
        links.addAll(original);
        var field = panel.vmf().reflect().propertyByName("links").orElseThrow();
        field.setDefault(List.of(d, e));
        var edits = new ArrayList<String>();
        var stream = panel.vmf().changes();
        var listener = stream.addPropertyListener("links", change -> change.referenceSetChange().ifPresent(edit ->
                edits.add(edit.indices() + ":" + edit.removed().size() + ":" + edit.added().size())));

        Panel.newBuilder().appendCollections(false).applyFrom(panel).applyTo(panel);
        assertThrows(IllegalArgumentException.class, () -> Panel.newBuilder().withLinks(List.of(a, a)).applyTo(panel));
        assertThrows(NullPointerException.class, () -> Panel.newBuilder().withLinks(Arrays.asList(a, null)).applyTo(panel));
        assertTrue(edits.isEmpty());

        stream.start();
        stream.startTransaction();
        var desired = List.of(e, x, b, y);
        Panel.newBuilder().withLinks(desired).appendCollections(false).applyTo(panel);
        stream.publishTransaction();
        stream.stop();
        listener.unsubscribe();
        assertEquals(List.of("[0, 2, 3]:3:0", "[0, 1]:2:2", "[1, 3]:0:2"), edits);
        assertSame(links, panel.getLinks());
        assertOrderedPanelLinks(panel, desired, List.of(a, c, d));

        var transaction = stream.transactions().get(0);
        assertTrue(transaction.isUndoable());
        transaction.undo();
        assertOrderedPanelLinks(panel, original, List.of(x, y));
        transaction.changes().forEach(change -> change.apply(panel));
        assertOrderedPanelLinks(panel, desired, List.of(a, c, d));

        assertTrue(field.isSet());
        assertEquals(List.of(d, e), field.getDefault());
        field.unset();
        assertFalse(field.isSet());
        assertOrderedPanelLinks(panel, List.of(d, e), List.of(a, b, c, x, y));
    }

    private static void assertOrderedPanelLinks(Panel panel, List<StationLink> present, List<StationLink> absent) {
        assertEquals(present.size(), panel.getLinks().size());
        for (int i = 0; i < present.size(); i++) {
            var link = present.get(i);
            assertSame(link, panel.getLinks().get(i));
            assertTrue(link.getPanels().contains(panel));
        }
        for (var link : absent) assertFalse(link.getPanels().contains(panel));
    }

    @Test public void builderCopiesMembershipAndKeepsItsCachedDefaultWithoutChangingOwners() {
        Station shared = Station.newInstance();
        Workshop owner = Workshop.newBuilder().withStations(shared).build();
        var source = DefaultNetwork.newBuilder().withNearby(shared).build();
        var builder = DefaultNetwork.newBuilder();
        Station seed = builder.build().getNearby().iterator().next();
        builder.applyFrom(source);

        DefaultNetwork target = DefaultNetwork.newInstance();
        var refs = target.getNearby();
        Station targetSeed = refs.iterator().next();
        refs.add(shared);
        var edits = new ArrayList<RefSetChange>();
        var listener = target.vmf().changes().addListener(change -> change.referenceSetChange().ifPresent(edits::add));
        builder.appendCollections(false).applyTo(target);
        listener.unsubscribe();

        assertSame(refs, target.getNearby());
        assertEquals(2, refs.size());
        assertTrue(refs.contains(seed));
        assertTrue(refs.contains(shared));
        assertSame(owner, shared.getWorkshop());
        assertSame(shared, source.getNearby().iterator().next());
        assertEquals(2, edits.size());
        assertSame(targetSeed, edits.get(0).removed().get(0));
        assertTrue(edits.get(0).added().isEmpty());
        assertSame(seed, edits.get(1).added().get(0));
        assertTrue(edits.get(1).removed().isEmpty());

        var field = target.vmf().reflect().propertyByName("nearby").orElseThrow();
        assertTrue(field.isSet());
        field.unset();
        assertSame(targetSeed, refs.iterator().next());
        assertFalse(field.isSet());
    }
}
