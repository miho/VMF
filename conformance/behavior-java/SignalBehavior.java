package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public class SignalBehavior implements DelegatedBehavior<SignalGauge> {
    public static int instances;
    private SignalGauge owner;
    private int calls;

    public SignalBehavior() {
        instances++;
    }

    @Override
    public void setCaller(SignalGauge caller) {
        owner = caller;
    }

    public int signal(int increment) {
        return owner.getLevel() + increment + ++calls;
    }
}
