package eu.mihosoft.vmf.portable.tree.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

// From test-suite vmftest/recursivelistener01 (Node) and events_undo_redo.
interface Node {
    String getName();

    @Container(opposite = "children")
    Node getParent();

    @Contains(opposite = "parent")
    Node[] getChildren();
}

// A chain of single-valued containments (cycle checks, single slot moves).
interface Box {
    String getName();

    @Contains(opposite = "outer")
    Box getInner();

    @Container(opposite = "inner")
    Box getOuter();
}
