package eu.mihosoft.vmf.conformance.executabledefaults;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class DefaultBehavior implements DelegatedBehavior<HookedDefaults> {
    private HookedDefaults owner;

    @Override public void setCaller(HookedDefaults owner) {
        this.owner = owner;
    }

    public void onHookedDefaultsInstantiated() {
        ExecutableDefaults.observeHook(owner.getLabel(), owner.getPriority());
        owner.setPriority(99);
    }
}
