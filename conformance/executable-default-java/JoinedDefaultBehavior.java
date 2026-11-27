package eu.mihosoft.vmf.conformance.executabledefaults;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class JoinedDefaultBehavior implements DelegatedBehavior<JoinedDefaults> {
    private JoinedDefaults owner;

    @Override public void setCaller(JoinedDefaults owner) {
        this.owner = owner;
    }

    public void onJoinedDefaultsInstantiated() {
        ExecutableDefaults.observeJoinedHook(owner.getLabel(), owner.getAlias());
        owner.setLabel("hook-label");
        owner.setAlias("hook-alias");
    }
}
