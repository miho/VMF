package eu.mihosoft.vmftest.complex.vflow.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface WithLocation {
    @DefaultValue(value="0")
    Integer getX();
    @DefaultValue(value="0")
    Integer getY();
}

@InterfaceOnly
interface WithDimensions {
    @DefaultValue(value="0")
    Integer getWidth();
    @DefaultValue(value="0")
    Integer getHeight();
}

@InterfaceOnly
interface WithId {
    String getId();
}

@InterfaceOnly
interface WithName {
    String getName();
}

@InterfaceOnly
interface WithType {
    @DefaultValue(value="\"default\"")
    String getType();
}

@InterfaceOnly
interface WithValue {
    Object getValue();
}


@InterfaceOnly
@DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectorDelegate")
interface Connector extends WithId, WithType, WithValue {
    @GetterOnly
    VNode getParent();

    @GetterOnly
    Connection[] getConnections();

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectorDelegate")
    ConnectionResult tryConnect(Connector c);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectorDelegate")
    ConnectionResult connect(Connector c);

}

interface Input extends Connector {
    @Container(opposite="inputs")
    VNode getParent();

    @Contains(opposite="receiver")
    Connection[] getConnections();
}

interface Output extends Connector {
    @Container(opposite="outputs")
    VNode getParent();

    @Contains(opposite="sender")
    Connection[] getConnections();
}

@DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.ConnectionDelegate")
interface Connection extends WithId, WithType {
    @Container(opposite="connections")
    Output getSender();

    @Container(opposite="connections")
    Input getReceiver();

    @Container(opposite="connections")
    VFlow getFlow();
}

interface ConnectionResult {
    Connection getConnection();
    boolean isSuccessful();
    @DefaultValue(value="\"\"")
    String getMessage();
}

interface VNode extends WithLocation, WithDimensions, WithId, WithType, WithValue, WithName {
    @Contains(opposite="parent")
    Input[] getInputs();

    @Contains(opposite="parent")
    Output[] getOutputs();

    @Container(opposite="nodes")
    VFlow getParent();

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VNodeDelegate")
    Input addInput( String type);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VNodeDelegate")
    Output addOutput(String type);
}

@DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
interface VFlow extends VNode {

    @Contains(opposite="parent")
    VNode[] getNodes();

    @Contains(opposite="flow")
    Connection[] getConnections();

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    ConnectionResult connect(Connector c1, Connector c2);
    
    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    ConnectionResult tryConnect(Connector c1, Connector c2);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    ConnectionResult connect(VNode n1, VNode n2, String type);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    VNode newNode(Object o);

    @DelegateTo(className="eu.mihosoft.vmftest.complex.vflow.VFlowDelegate")
    VFlow newSubFlow(Object o);

}