package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class AdditionalHookBehavior implements DelegatedBehavior<RootHookOverride> {
    private RootHookOverride owner;

    @Override
    public void setCaller(RootHookOverride owner) {
        this.owner = owner;
    }

    public void onRootHookOverrideInstantiated() {
        RootHookDefaultsConformance.beforeHook(owner, owner.asReadOnly(), owner.getQueue().size(),
                owner.getEarlier().size(), owner.getZulu(), "hook-child-local");
        owner.getQueue().add(ReferenceDefaultBindings.target("hook-member"));
    }
}
