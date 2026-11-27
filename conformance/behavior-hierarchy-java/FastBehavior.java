package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.ArrayList;
import java.util.List;

public class FastBehavior implements DelegatedBehavior<FastEngine> {
    public static final List<String> HOOK_VALUES = new ArrayList<>();
    private FastEngine owner;

    @Override
    public void setCaller(FastEngine caller) {
        owner = caller;
    }

    public void onFastEngineInstantiated() {
        HOOK_VALUES.add(owner.getPower() + ":" + owner.getBonus());
    }

    public int transform(int delta) {
        return owner.getPower() + owner.getBonus() + delta;
    }
}
