package eu.mihosoft.vmf.portable.identity.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.VMFEquals;
import eu.mihosoft.vmf.core.VMFModel;

// From test-suite vmftest/setteridentity (SetterIdentityTest), with int instead of double.
// Structural native equality makes equal-but-distinct objects easy to confuse with each other.
@VMFModel(equality = VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL)
@InterfaceOnly
interface Element {
    @Container
    Element getParent();
}

@InterfaceOnly
interface Expression extends Element {
}

interface NumberExpression extends Expression {
    int getValue();
}

interface BinaryExpression extends Expression {
    String getOperator();

    @Contains(opposite = "parent")
    Expression getLeft();

    @Contains(opposite = "parent")
    Expression getRight();

    @Contains(opposite = "parent")
    Expression[] getArguments();
}

interface Holder {
    NumberExpression getValue();
}
