package eu.mihosoft.vmftest.parentcontainment01.vmfmodel;


import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;


@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmftest.parentcontainment01.CodeEntityDelegate")
interface CodeEntity {
    CodeEntity getParent();

    @DelegateTo(className = "eu.mihosoft.vmftest.parentcontainment01.CodeEntityDelegate")
    CodeEntity root();
}

@InterfaceOnly
interface Expression extends CodeEntity {

}

interface OperatorExpression extends Expression {
    Expression getLeft();
    Expression getRight();
}

interface NumberExpression extends Expression {
    Double getValue();
}
