package eu.mihosoft.vmftest.referenceends.vmfmodel;

import eu.mihosoft.vmf.core.*;
import java.util.Set;
import static eu.mihosoft.vmf.core.Multiplicity.Kind.ORDERED_SET;

@InterfaceOnly
interface StationOwner {
    @Contains(opposite = "workshop")
    @Multiplicity(lower = 1, upper = 2, kind = ORDERED_SET)
    Station[] getStations();
}

interface Workshop extends StationOwner {
    @Override Station[] getStations();
    Set<Station> getFavorites();
}

interface BoundedPicks {
    @Multiplicity(upper = 2)
    Station[] getTrail();
    @Multiplicity(kind = ORDERED_SET, upper = 2)
    Station[] getChoices();
}

@VMFEquals(VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL)
interface Station {
    @Multiplicity(lower = 1)
    String getName();
    @Container(opposite = "stations") Workshop getWorkshop();
}

@VMFEquals(VMFEquals.EqualsType.ALL)
interface Network {
    Set<Station> getNearby();
}

interface MixedGroup {
    @Contains Station[] getMembers();
    Set<Station> getFavorites();
}

interface Team {
    @Refers(opposite = "teams") Set<Member> getMembers();
}

@VMFEquals(VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL)
interface Member {
    String getName();
    @Refers(opposite = "members") Team[] getTeams();
}

interface Panel {
    @Multiplicity(kind = ORDERED_SET)
    @Refers(opposite = "panels") StationLink[] getLinks();
}

interface StationLink {
    @Refers(opposite = "links") Set<Panel> getPanels();
}

interface Roster {
    @Refers(opposite = "roster") Set<Registration> getRegistrations();
}
interface Registration {
    @Refers(opposite = "registrations") Roster getRoster();
}

interface DefaultNetwork {
    @DefaultValue("java.util.List.of(Station.newInstance())")
    Set<Station> getNearby();
}

@Immutable interface Label { String getName(); }

@Immutable interface LabelBook {
    @DefaultValue("java.util.List.of(Label.newBuilder().withName(\"initial\").build())")
    Set<Label> getLabels();
}
