package eu.mihosoft.vmf.conformance.inheritedopposites.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;

import java.util.Set;

interface DispatchBoard {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) DispatchTeam[] getTeams();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) DispatchMember[] getPeople();
}

interface DispatchHeader {
    String getName();
}

@InterfaceOnly interface DispatchTeam {
    @Refers(opposite = "team") Set<DispatchMember> getMembers();
    @Refers(opposite = "leadOf") DispatchMember getLead();
}

@InterfaceOnly interface DispatchMember {
    @Refers(opposite = "members") DispatchTeam getTeam();
    @Refers(opposite = "lead") DispatchTeam getLeadOf();
}

interface SpecializedDispatchTeam extends DispatchHeader, DispatchTeam { }

interface SpecializedDispatchMember extends DispatchHeader, DispatchMember { }

interface ConcreteDispatchTeam {
    @Refers(opposite = "team") Set<ConcreteDispatchMember> getMembers();
}

interface ConcreteDispatchMember {
    @Refers(opposite = "members") ConcreteDispatchTeam getTeam();
}

interface NamedConcreteDispatchTeam extends DispatchHeader, ConcreteDispatchTeam { }

interface NamedConcreteDispatchMember extends DispatchHeader, ConcreteDispatchMember { }
