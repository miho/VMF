package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class JournalCheckpointBehavior implements DelegatedBehavior<HookedJournalCheckpoint> {
    private static int initialized;
    private HookedJournalCheckpoint owner;
    private int invocations;

    public static int hookCount() {
        return initialized;
    }

    @Override public void setCaller(HookedJournalCheckpoint caller) {
        owner = caller;
    }

    public void onHookedJournalCheckpointInstantiated() {
        initialized++;
        owner.getSlots().add(1);
    }

    public int invocation() {
        return ++invocations;
    }
}
