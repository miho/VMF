package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public class LookupBehavior implements DelegatedBehavior<IntLookup> {
    private IntLookup owner;

    @Override
    public void setCaller(IntLookup caller) {
        owner = caller;
    }

    public int metric(int delta) {
        return (owner.getValue() == null ? 0 : owner.getValue()) + delta;
    }
}
