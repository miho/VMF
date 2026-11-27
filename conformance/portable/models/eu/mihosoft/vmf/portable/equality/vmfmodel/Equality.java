package eu.mihosoft.vmf.portable.equality.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;

// From test-suite vmftest/equals (EqualsTest, 2019) and vmftest/setteridentity,
// without the @VMFEquals modes (content equality does not depend on them).
@InterfaceOnly
interface WithName {
    String getName();
}

interface Model extends WithName {
    Reference getReference();

    @Contains(opposite = "parent")
    Child getChild();
}

interface Reference extends WithName {
}

interface Child extends WithName {
    @Container(opposite = "child")
    Model getParent();
}

interface OtherModel extends WithName {
    int getValue();
}

interface ListModel extends WithName {
    @Contains(opposite = "parent")
    ListChild[] getChildren();

    String[] getTags();
}

interface ListChild extends WithName {
    @Container(opposite = "children")
    ListModel getParent();

    Reference getReference();

    ListChild getSibling();
}
