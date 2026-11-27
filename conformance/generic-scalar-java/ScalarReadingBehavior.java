package eu.mihosoft.vmf.conformance.genericscalars;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class ScalarReadingBehavior implements DelegatedBehavior<HookedScalarReading> {
    private HookedScalarReading owner;
    private int runs;

    @Override public void setCaller(HookedScalarReading caller) { owner = caller; }

    public void onHookedScalarReadingInstantiated() {
        if (owner.getSample() != null) {
            throw new AssertionError("The generic scalar starts unset in the construction hook");
        }
        runs++;
    }

    public int hookRuns() { return runs; }
}
