package eu.mihosoft.vmftest.equals.vmfmodel;

import eu.mihosoft.vmf.core.*;

@VMFModel(
        equalsDefaultImpl = VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL
)

//@InterfaceOnly
interface WithName {
    String getName();
}


interface EqualsTestModel extends WithName {

    AReference getReference();

    @Contains(opposite = "parent")
    Child getChild();
}

interface AReference extends WithName {
}

interface Child extends WithName {

    @Container(opposite = "child")
    EqualsTestModel getParent();
}

interface EqualsTestModel2 extends WithName {

    int getValue();
}

@VMFEquals(value = VMFEquals.EqualsType.ALL)
interface EqualsTestModelAllEq extends WithName {

    int getValue();

    AReference getReference();
}

@VMFEquals(value = VMFEquals.EqualsType.INSTANCE)
interface EqualsTestModelInstanceEq extends WithName {

    int getValue();

    AReference getReference();
}
