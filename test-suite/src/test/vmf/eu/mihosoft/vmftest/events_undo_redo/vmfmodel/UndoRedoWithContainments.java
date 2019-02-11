package eu.mihosoft.vmftest.events_undo_redo.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface ParentListContainment {
    @Contains(opposite="parent")
    ChildListContainment[] getChildren();
}

interface ChildListContainment {
    @Container(opposite="children")
    ParentListContainment getParent();
}

interface ParentSingleContainment {
    @Contains(opposite="parent")
    ChildSingleContainment getChild();
}

interface ChildSingleContainment {
    @Container(opposite="child")
    ParentSingleContainment getParent();
}