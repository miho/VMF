package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import java.util.ArrayList;
import java.util.List;

public class FlowChildBehavior implements DelegatedBehavior<FlowChild> {
    static final List<Integer> hookIds = new ArrayList<>();
    static int instances;
    private FlowChild caller;
    private int calls;

    public FlowChildBehavior() { instances++; }
    @Override public void setCaller(FlowChild caller) { this.caller = caller; }
    public void onFlowChildInstantiated() { hookIds.add(originId()); }

    public VList<FlowScope> parentScopes() {
        calls++;
        var result = VList.<FlowScope>newInstance(new ArrayList<>());
        if (caller instanceof FlowScope) {
            result.add((FlowScope) caller);
            result.add(null);
        }
        return result;
    }

    public int calls() { return calls; }
    public int originId() { return ((FlowId) caller).getId(); }
}
