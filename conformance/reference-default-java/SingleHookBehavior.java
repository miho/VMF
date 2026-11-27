package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class SingleHookBehavior implements DelegatedBehavior<SingleHookSelection> {
    private SingleHookSelection owner;

    @Override
    public void setCaller(SingleHookSelection owner) {
        this.owner = owner;
    }

    public void onSingleHookSelectionInstantiated() {
        RootHookDefaultsConformance.beforeHook(owner, owner.asReadOnly(), owner.getQueue().size(),
                owner.getEarlier().size(), owner.getZulu(), "hook-single");
        owner.getQueue().add(ReferenceDefaultBindings.target("hook-member"));
        owner.getEarlier().add(90);
        owner.setZulu("hook");
    }
}
