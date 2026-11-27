package eu.mihosoft.vmf.conformance.equality.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.Set;

@VMFEquals(VMFEquals.EqualsType.ALL)
interface EqualityNode {
    String getName();
    Double getReading();
    @IgnoreEquals String getNote();

    EqualityNode getNext();
    EqualityNode[] getLinks();
    Set<EqualityNode> getMembers();
    EqualityIdentity getExternal();

    @Container(opposite = "children") EqualityContainer getContainer();
}

@VMFEquals(VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL)
interface EqualityContainer {
    String getName();
    EqualityIdentity getExternal();
    @Contains EqualityNode getChild();
    @Contains(opposite = "container")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    EqualityNode[] getChildren();
}

@VMFEquals(VMFEquals.EqualsType.INSTANCE)
interface EqualityIdentity {
    String getName();
}

@InterfaceOnly
interface EqualityDetails {
    String getExtra();
}

@VMFEquals(VMFEquals.EqualsType.ALL)
interface EqualityDerived extends EqualityNode, EqualityDetails {
}
