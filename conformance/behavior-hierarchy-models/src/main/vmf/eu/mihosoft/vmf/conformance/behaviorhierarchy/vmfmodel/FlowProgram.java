package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface FlowElement {
    String getLabel();
}

@InterfaceOnly
interface FlowId extends FlowElement {
    @DefaultValue("11") int getId();
}

@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.FlowChildBehavior")
interface FlowChild {
    VList<FlowScope> parentScopes();
    int calls();
    int originId();
}

@InterfaceOnly
interface FlowStatement extends FlowElement, FlowId, FlowChild { }

@InterfaceOnly
interface FlowScope extends FlowId, FlowChild {
    String getScopeName();
}

interface FlowBlock extends FlowStatement, FlowScope { }

interface FlowProcedure extends FlowElement, FlowScope { }

@InterfaceOnly
interface FlowExpression extends FlowElement, FlowId, FlowChild { }

@InterfaceOnly
interface FlowOperator extends FlowExpression {
    String getOperator();
}

interface FlowAddition extends FlowExpression, FlowOperator { }

interface FlowProgram {
    @Contains FlowStatement getBlock();
    @Contains FlowExpression getExpression();
    @Contains FlowProcedure getProcedure();
    FlowScope getLookup();
}
