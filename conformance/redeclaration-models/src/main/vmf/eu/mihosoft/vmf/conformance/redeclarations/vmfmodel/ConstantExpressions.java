package eu.mihosoft.vmf.conformance.redeclarations.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.PropertyOrder;

@InterfaceOnly
interface Expression {
    int getId();
}

@InterfaceOnly
interface ConstExpression {
    @GetterOnly
    Object getValue();
}

interface IntegerExpression extends Expression, ConstExpression {
    @DefaultValue("null")
    @PropertyOrder(index = 0)
    Integer getValue();
}

interface FloatingExpression extends Expression, ConstExpression {
    @DefaultValue("null")
    @PropertyOrder(index = 0)
    Double getValue();
}

interface BooleanExpression extends Expression, ConstExpression {
    @DefaultValue("null")
    @PropertyOrder(index = 0)
    Boolean getValue();
}

interface StringExpression extends Expression, ConstExpression {
    @DefaultValue("null")
    @PropertyOrder(index = 0)
    String getValue();
}
