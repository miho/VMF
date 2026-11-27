package eu.mihosoft.vmf.conformance.ordinarycontainment.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Multiplicity;

interface LegacyLayout {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    LegacyContainer[] getContainers();

    LegacyChild getFeatured();
}

interface LegacyContainer {
    String getName();

    @Contains(opposite = "owner")
    LegacyChild[] getChildren();
}

interface LegacyChild {
    String getName();

    @Container(opposite = "children")
    LegacyContainer getOwner();
}

interface BuilderCycleNode {
    String getCaption();
    @Contains BuilderCycleNode[] getChildren();
}
