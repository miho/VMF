package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface SpineIdentified { String getName(); }

@InterfaceOnly
interface SpineCategory { @DefaultValue("\"default\"") String getChannel(); }

@InterfaceOnly
interface SpineMetadata extends SpineCategory { String getLabel(); }

@InterfaceOnly
interface SpinePosition { int getX(); }

@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.PrimarySpineExchange.ConnectorBinding")
interface SpineConnector extends SpineIdentified, SpineMetadata {
    @GetterOnly SpineNode getOwner();
    boolean connect(SpineConnector other);
    int connectionsTried();
}

interface SpinePort extends SpineConnector {
    @Container(opposite = "ports") SpineNode getOwner();
}

interface SpineNode extends SpinePosition, SpineIdentified, SpineMetadata {
    @Contains(opposite = "owner") SpinePort[] getPorts();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.PrimarySpineExchange.NodeBinding")
    SpinePort addPort(String channel);

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.PrimarySpineExchange.NodeBinding")
    int portsCreated();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.PrimarySpineExchange.EndpointBinding")
interface SpineEndpoint extends SpineIdentified, SpineMetadata { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.PrimarySpineExchange.FlowBinding")
interface SpineFlow extends SpineNode {
    @Contains SpineNode[] getNodes();
    @Contains SpineEndpoint getEndpoint();
    SpineConnector getFeatured();

    SpineNode newNode(String name);
    boolean connect(SpineConnector from, SpineConnector to);
    boolean connect(SpineNode from, SpineNode to, String channel);
}
