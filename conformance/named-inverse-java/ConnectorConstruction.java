package eu.mihosoft.vmf.conformance.namedinverse;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.IdentityHashMap;
import java.util.Map;

public final class ConnectorConstruction implements DelegatedBehavior<Connector> {
    static final Map<Connector, VList<Edge>> OBSERVED = new IdentityHashMap<>();
    private Connector caller;

    @Override
    public void setCaller(Connector caller) {
        this.caller = caller;
    }

    public void onConnectorInstantiated() {
        OBSERVED.put(caller, caller.getLinks());
    }
}
