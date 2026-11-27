package eu.mihosoft.vmf.conformance.inheritedopposites.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;

interface SequenceRoleBoard {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) OrderedRoleTeam[] getOrderedTeams();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) OrderedRolePerson[] getOrderedPeople();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) ListRoleTeam[] getListTeams();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) ListRolePerson[] getListPeople();
}

interface OrderedRoleTeam {
    String getName();
    @Refers(opposite = "team")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    OrderedRoleMember[] getMembers();
}

@InterfaceOnly
interface OrderedRoleMember {
    @GetterOnly @Refers(opposite = "members") OrderedRoleTeam getTeam();
}

interface OrderedRolePerson extends OrderedRoleMember {
    String getName();
}

interface ListRoleTeam {
    String getName();
    @Refers(opposite = "team") ListRoleMember[] getMembers();
}

@InterfaceOnly
interface ListRoleMember {
    @GetterOnly @Refers(opposite = "members") ListRoleTeam getTeam();
}

interface ListRolePerson extends ListRoleMember {
    String getName();
}
