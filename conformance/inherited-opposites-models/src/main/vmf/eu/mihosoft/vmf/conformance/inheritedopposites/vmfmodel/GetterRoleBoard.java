package eu.mihosoft.vmf.conformance.inheritedopposites.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;

import java.util.Set;

interface GetterRoleBoard {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) GetterRoleTeam[] getTeams();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) GetterRolePerson[] getPeople();
}

interface GetterRoleTeam {
    String getName();
    @Refers(opposite = "team") Set<GetterRoleMember> getMembers();
}

@InterfaceOnly
interface GetterRoleMember {
    @GetterOnly @Refers(opposite = "members") GetterRoleTeam getTeam();
}

interface GetterRolePerson extends GetterRoleMember {
    String getName();
}
