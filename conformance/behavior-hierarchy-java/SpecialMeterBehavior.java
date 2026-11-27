package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import java.util.ArrayList;
import java.util.List;

public class SpecialMeterBehavior implements DelegatedBehavior<SpecialCell> {
    static final List<String> hooks = new ArrayList<>();
    private SpecialCell caller;

    @Override public void setCaller(SpecialCell caller) { this.caller = caller; }
    public void onSpecialCellInstantiated() { hooks.add(caller.getEnergy() + ":" + caller.getWeight()); }
    public int advance(int delta) { return 100 + caller.getWeight() + delta; }
}
