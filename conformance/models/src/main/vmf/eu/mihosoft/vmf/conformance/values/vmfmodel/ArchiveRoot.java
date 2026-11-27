package eu.mihosoft.vmf.conformance.values.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Required;

import java.util.Set;

interface ArchiveRoot {
    @Contains
    @Required
    ArchiveNode getHead();

    @Multiplicity(lower = 2)
    ArchiveNode[] getReview();
}

interface ArchiveNode {
    String getName();

    @Contains(opposite = "parent")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    ArchiveNode[] getChildren();

    @Container(opposite = "children")
    ArchiveNode getParent();

    @Contains(opposite = "insetOf")
    ArchiveNode getInset();

    @Container(opposite = "inset")
    ArchiveNode getInsetOf();

    ArchiveNode getAnchor();

    Set<ArchiveNode> getPeers();

    ArchiveNode[] getTrail();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    ArchiveNode[] getFeatured();
}

interface ArchiveOwner {
    @Contains(opposite = "owner")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    ArchiveOwnedNode[] getNodes();
}

@InterfaceOnly
interface ArchiveOwnedNode {
    @Container(opposite = "nodes")
    ArchiveOwner getOwner();
}

interface ArchiveOwnedLeaf extends ArchiveOwnedNode {
    @Required
    String getLabel();
}
