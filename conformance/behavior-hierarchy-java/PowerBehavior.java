package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import java.util.ArrayList;
import java.util.List;

public class PowerBehavior implements DelegatedBehavior<PoweredCell> {
    static final List<String> hooks = new ArrayList<>();
    static int instances;
    private PoweredCell caller;

    public PowerBehavior() { instances++; }
    @Override public void setCaller(PoweredCell caller) { this.caller = caller; }
    public void onPoweredCellInstantiated() {
        hooks.add(caller instanceof ComposedCell ? "composed:" + caller.getEnergy() + ":" +
                ((ComposedCell) caller).getWeight() : "powered:" + caller.getEnergy());
    }
    public int amplify(int delta) { return caller.getEnergy() * delta; }
}
