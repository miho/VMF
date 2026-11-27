package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

public final class HookedReferenceBehavior implements DelegatedBehavior<HookSelection> {
    private HookSelection owner;

    @Override
    public void setCaller(HookSelection owner) {
        this.owner = owner;
    }

    public void onHookSelectionInstantiated() {
        HookReferenceDefaultBindings.events.add("hook");
        var queue = HookReferenceDefaultsConformance.field(owner, "queue");
        var projected = HookReferenceDefaultsConformance.field(owner.asReadOnly(), "queue");
        HookReferenceDefaultsConformance.check(owner.getQueue().isEmpty() && owner.getEarlier().isEmpty()
                        && owner.getZulu() == null && queue.getDefault() == null && queue.isSet(),
                "The inherited hook receives initialized empty collections before any default provider");
        verifyUnavailable(projected::getDefault);
        verifyUnavailable(projected::isSet);

        owner.getQueue().add(ReferenceDefaultBindings.target("hook-member"));
        owner.setZulu("hook");
        owner.getEarlier().add(90);
    }

    private static void verifyUnavailable(Runnable read) {
        try {
            read.run();
        } catch (NullPointerException expected) {
            return;
        }
        throw new AssertionError("The read-only reference fallback is unavailable during the hook");
    }
}
