package eu.mihosoft.vmf.conformance.ordinarycontainment.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.VMFEquals;

interface ValueMatchedList {
    @Contains(opposite = "owner") ValueMatchedCard[] getChildren();
    ValueMatchedCard[] getReferences();
}

@VMFEquals(VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL)
interface ValueMatchedCard {
    @IgnoreEquals String getId();
    String getCaption();
    @Container(opposite = "children") ValueMatchedList getOwner();
}
