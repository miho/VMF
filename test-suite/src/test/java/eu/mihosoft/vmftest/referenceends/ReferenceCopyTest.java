package eu.mihosoft.vmftest.referenceends;

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
}
