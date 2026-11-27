package eu.mihosoft.vmf.portable.events.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

// From test-suite vmftest/observableprop (2019) and vmftest/events_undo_redo (2019).
interface ObserveMyProperties {
    String getName();

    Integer[] getValues();
}

interface ParentListContainment {
    @Contains(opposite = "parent")
    ChildListContainment[] getChildren();
}

interface ChildListContainment {
    @Container(opposite = "children")
    ParentListContainment getParent();

    String getName();
}

interface ParentSingleContainment {
    @Contains(opposite = "parent")
    ChildSingleContainment getChild();
}

interface ChildSingleContainment {
    @Container(opposite = "child")
    ParentSingleContainment getParent();

    String getName();
}
