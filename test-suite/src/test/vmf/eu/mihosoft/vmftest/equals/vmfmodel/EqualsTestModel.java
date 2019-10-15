package eu.mihosoft.vmftest.equals.vmfmodel;

import eu.mihosoft.vmf.core.*;

@VMFModel(
        equalsDefaultImpl = VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL
)


interface EqualsTestModel {
    String getName();

    AReference getReference();

    @Contains(opposite = "parent")
    Child getChild();
}

interface AReference {
    String getName();
}

interface Child {
    String getName();

    @Container(opposite = "child")
    EqualsTestModel getParent();
}
