package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class RootHookBehavior implements DelegatedBehavior<RootHookSelection> {
    private RootHookSelection owner;

    @Override
    public void setCaller(RootHookSelection owner) {
        this.owner = owner;
    }

    public void onRootHookSelectionInstantiated() {
        if (owner instanceof RootHookSelectionLeaf) {
            var leaf = (RootHookSelectionLeaf) owner;
            RootHookDefaultsConformance.check(leaf.getAhead() == null, "The hook sees the actual leaf before leaf defaults");
            leaf.setAhead("hook-child");
        }
        RootHookDefaultsConformance.beforeHook(owner, owner.asReadOnly(), owner.getQueue().size(),
                owner.getEarlier().size(), owner.getZulu(), owner instanceof RootHookSelectionLeaf ? "hook-leaf" : "hook-root");
        owner.getQueue().add(ReferenceDefaultBindings.target("hook-member"));
        owner.getEarlier().add(90);
        owner.setZulu("hook");
    }
}
