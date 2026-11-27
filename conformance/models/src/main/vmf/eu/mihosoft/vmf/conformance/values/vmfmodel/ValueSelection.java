package eu.mihosoft.vmf.conformance.values.vmfmodel;

import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.VMFEquals;
import java.util.Set;

@VMFEquals(VMFEquals.EqualsType.ALL)
@Immutable
interface ValueLabel {
    String getText();
}

@Immutable
interface ValueNode {
    ValueLabel getLabel();
    ValueNode getNext();
}

interface ValueSelection {
    ValueLabel getCurrent();
    ValueLabel getSecond();
    Set<ValueLabel> getSelected();
    ValueNode getFirst();
    Set<ValueNode> getNodes();
}
