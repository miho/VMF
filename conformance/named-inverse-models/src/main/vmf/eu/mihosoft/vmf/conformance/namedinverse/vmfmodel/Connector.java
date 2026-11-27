package eu.mihosoft.vmf.conformance.namedinverse.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface ConnectorIdentity {
    String getIdentifier();
}

@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmf.conformance.namedinverse.ConnectorConstruction")
interface Connector extends ConnectorIdentity {
    @GetterOnly Node getParent();
    @GetterOnly Edge[] getLinks();
}

interface Input extends Connector {
    @Container(opposite = "inputs") Node getParent();
    @Contains(opposite = "receiver") Edge[] getLinks();
}

interface Output extends Connector {
    @Container(opposite = "outputs") Node getParent();
    @Contains(opposite = "sender") Edge[] getLinks();
}

interface Edge {
    String getName();
    @Container(opposite = "links") Input getReceiver();
    @Container(opposite = "links") Output getSender();
    @Container(opposite = "links") Flow getFlow();
}

interface Node {
    @Container(opposite = "nodes") Flow getFlow();
    @Contains(opposite = "parent") Input[] getInputs();
    @Contains(opposite = "parent") Output[] getOutputs();
}

interface Flow {
    @Contains(opposite = "flow") Node[] getNodes();
    @Contains(opposite = "flow") Edge[] getLinks();
}
