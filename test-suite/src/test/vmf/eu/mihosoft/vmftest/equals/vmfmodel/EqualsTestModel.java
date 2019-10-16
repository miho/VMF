package eu.mihosoft.vmftest.equals.vmfmodel;

import eu.mihosoft.vmf.core.*;
import static eu.mihosoft.vmf.core.VMFEquals.EqualsType.*;

@VMFModel(
    equality = CONTAINMENT_AND_EXTERNAL
)


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

@VMFEquals(ALL)
interface EqualsTestModelAllEq extends WithName {

    int getValue();

    AReference getReference();
}

@VMFEquals(INSTANCE)
interface EqualsTestModelInstanceEq extends WithName {

    int getValue();

    AReference getReference();
}
