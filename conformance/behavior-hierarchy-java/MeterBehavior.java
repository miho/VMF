package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import java.util.ArrayList;
import java.util.List;

public class MeterBehavior implements DelegatedBehavior<MeteredCell> {
    static final List<String> hooks = new ArrayList<>();
    static int instances;
    private MeteredCell caller;
    private int calls;

    public MeterBehavior() { instances++; }
    @Override public void setCaller(MeteredCell caller) { this.caller = caller; }
    public void onMeteredCellInstantiated() {
        hooks.add(caller instanceof StandaloneMeter ? "standalone:" + caller.getWeight() + ":" +
                ((StandaloneMeter) caller).getVoltage() : "metered:" + caller.getWeight());
    }
    public int advance(int delta) { return caller.getWeight() + delta + ++calls; }
    public int calls() { return calls; }
    public boolean callerIs(MeteredCell value) { return caller == value; }
}
