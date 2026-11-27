package eu.mihosoft.vmf.conformance.inheritedopposites.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;

import java.util.Set;

interface RelationshipSite {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) PrimaryTeam[] getPrimaryTeams();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) AbstractTeam[] getAbstractTeams();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Owner[] getOwners();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) GenericTeam[] getGenericTeams();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) GenericMember[] getPeople();
}

interface PrimaryTeam {
    @Contains(opposite = "owner") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) PrimaryPart[] getParts();
    @Refers(opposite = "team") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) PrimaryPart[] getFeatured();
}

interface PrimaryTeamSpecialization extends PrimaryTeam { }

interface PrimaryPart {
    @Container(opposite = "parts") PrimaryTeam getOwner();
    @Refers(opposite = "featured") PrimaryTeam getTeam();
}

interface PrimaryPartSpecialization extends PrimaryPart { }

@InterfaceOnly
interface AbstractTeam {
    @Contains(opposite = "owner") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) AbstractPart[] getParts();
    @Refers(opposite = "team") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) AbstractPart[] getFeatured();
}

interface ConcreteTeam extends AbstractTeam { }

@InterfaceOnly
interface AbstractPart {
    @Container(opposite = "parts") AbstractTeam getOwner();
    @Refers(opposite = "featured") AbstractTeam getTeam();
}

interface ConcretePart extends AbstractPart { }

@InterfaceOnly
interface OwnerOf<T extends OwnedTarget> {
    @Contains(opposite = "owner") T getPrimary();
    @Refers(opposite = "selector") T getHighlighted();
}

interface Owner extends OwnerOf<Owned> {
    @Override @Annotation(key = "vmf:jackson:rename", value = "owned_item") Owned getPrimary();
    @Override Owned getHighlighted();
}

@InterfaceOnly
interface OwnedTarget {
    @Container(opposite = "primary") Owner getOwner();
    @Refers(opposite = "highlighted") Owner getSelector();
}

interface Owned extends OwnedTarget { }

interface SpecializedOwned extends Owned { }

@InterfaceOnly
interface MemberBase {
    @Refers(opposite = "members") GenericTeam getTeam();
}

interface GenericMember extends MemberBase { }

interface SpecializedMember extends GenericMember { }

@InterfaceOnly
interface TeamOf<T extends MemberBase> {
    @Refers(opposite = "team") Set<T> getMembers();
}

interface GenericTeam extends TeamOf<GenericMember> { }

@InterfaceOnly
interface CompanionHolder {
    @Contains(opposite = "holder") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) CompanionChild[] getParts();
    @Contains(opposite = "directHolder") CompanionChild getChild();
}

interface HolderBase { }

interface ConcreteHolder extends HolderBase, CompanionHolder { }

interface CompanionChild {
    @Container(opposite = "parts") CompanionHolder getHolder();
    @Container(opposite = "child") CompanionHolder getDirectHolder();
}
