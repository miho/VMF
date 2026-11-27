package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.Set;

@InterfaceOnly
interface NamedOwnedRole {
    @GetterOnly String getLabel();
    @GetterOnly @Container(opposite = "single") NamedRoleFolder getSingleOwner();
    @GetterOnly @Container(opposite = "ordered") NamedRoleFolder getOrderedOwner();
    @GetterOnly @Container(opposite = "ordinary") NamedRoleFolder getOrdinaryOwner();
    @GetterOnly @Container(opposite = "unordered") NamedRoleFolder getUnorderedOwner();
}

interface NamedOwnedNote extends NamedOwnedRole {
    String getNote();
}

interface NamedOwnedTask extends AssetData, NamedOwnedRole {
    int getTaskNumber();
}

interface NamedRoleFolder {
    String getName();
    @Contains(opposite = "singleOwner") NamedOwnedRole getSingle();
    @Contains(opposite = "orderedOwner") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) NamedOwnedRole[] getOrdered();
    @Contains(opposite = "ordinaryOwner") NamedOwnedRole[] getOrdinary();
    @Contains(opposite = "unorderedOwner") Set<NamedOwnedRole> getUnordered();
    NamedOwnedRole getSelected();
}
