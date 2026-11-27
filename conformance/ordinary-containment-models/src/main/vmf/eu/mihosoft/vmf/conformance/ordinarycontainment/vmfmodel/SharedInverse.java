package eu.mihosoft.vmf.conformance.ordinarycontainment.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface BranchNode {
    @Container BranchNode getParent();
    String getCode();
}

interface Twig extends BranchNode {}

interface Bough extends BranchNode {
    @Contains(opposite = "parent") BranchNode getLeft();
    @Contains(opposite = "parent") BranchNode getRight();
    @Contains(opposite = "parent") BranchNode[] getBranches();
    @Contains BranchNode getStaging();
}

interface ForestLayout {
    @Contains Bough[] getRoots();
}
