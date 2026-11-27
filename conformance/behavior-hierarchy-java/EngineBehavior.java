package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EngineBehavior implements DelegatedBehavior<Engine> {
    public static int instances;
    public static final List<String> HOOK_VALUES = new ArrayList<>();
    private Engine owner;

    public EngineBehavior() {
        instances++;
    }

    @Override
    public void setCaller(Engine caller) {
        owner = caller;
    }

    public void onEngineInstantiated() {
        if (owner instanceof InheritedEngine) {
            HOOK_VALUES.add("inherited:" + owner.getPower() + ":" + ((InheritedEngine) owner).getRate());
        } else {
            HOOK_VALUES.add("base:" + owner.getPower());
        }
    }

    public int transform(int delta) {
        return owner.getPower() + delta;
    }

    public String label(String prefix) {
        return Objects.toString(prefix, "") + ":" + owner.getPower();
    }
}
