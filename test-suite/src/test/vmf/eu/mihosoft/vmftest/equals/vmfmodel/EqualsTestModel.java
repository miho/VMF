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


@VMFEquals(CONTAINMENT_AND_EXTERNAL)
interface EqualsTestContainmentEqListChild extends WithName {

    @Container(opposite = "children")
    EqualsTestContainmentEqList getParent();

}


@VMFEquals(CONTAINMENT_AND_EXTERNAL)
interface EqualsTestContainmentEqList extends WithName {

    @Contains(opposite = "parent")
    EqualsTestContainmentEqListChild[] getChildren();

}

@VMFEquals(INSTANCE)
interface EqualsTestInstanceEqListChild extends WithName {

    @Container(opposite = "children")
    EqualsTestInstanceEqList getParent();

}


@VMFEquals(INSTANCE)
interface EqualsTestInstanceEqList extends WithName {

    @Contains(opposite = "parent")
    EqualsTestInstanceEqListChild[] getChildren();

}

