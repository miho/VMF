package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public class GaugeDiagnostics implements DelegatedBehavior<MutableGauge> {
    public static int instances;
    private MutableGauge owner;

    public GaugeDiagnostics() {
        instances++;
    }

    @Override
    public void setCaller(MutableGauge caller) {
        owner = caller;
    }

    public String diagnose(boolean verbose) {
        return verbose ? owner.getState() : "ok";
    }
}
