package eu.mihosoft.vmf.portable.equalitymodes.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.VMFEquals;
import eu.mihosoft.vmf.core.VMFModel;

import static eu.mihosoft.vmf.core.VMFEquals.EqualsType.ALL;
import static eu.mihosoft.vmf.core.VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL;
import static eu.mihosoft.vmf.core.VMFEquals.EqualsType.INSTANCE;

// From test-suite vmftest/equals (testEqualsAll, testEqualsInstance, testEqualInstanceEq).
@VMFModel(equality = CONTAINMENT_AND_EXTERNAL)
interface Target {
    String getName();
}

@VMFEquals(ALL)
interface AllEq {
    String getName();

    int getValue();

    Target getReference();
}

@VMFEquals(INSTANCE)
interface InstanceEq {
    String getName();

    int getValue();

    Target getReference();
}

interface DefaultEq {
    String getName();

    Target getReference();

    @Contains(opposite = "parent")
    DefaultEqChild[] getChildren();
}

interface DefaultEqChild {
    String getName();

    @Container(opposite = "children")
    DefaultEq getParent();
}
