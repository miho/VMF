package eu.mihosoft.vmftest.recursivelistener01.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

public interface Node {
    @Container(opposite = "children")
    Node getParent();

    @Contains(opposite = "parent")
    Node[] getchildren();

    String getName();
}
