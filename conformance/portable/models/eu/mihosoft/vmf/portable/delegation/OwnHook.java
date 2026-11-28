package eu.mihosoft.vmf.portable.delegation;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

/** The construction hook of OwnHooked: appends "own" to origin, so that a second hook would show. */
public class OwnHook implements DelegatedBehavior<OwnHooked> {
    private OwnHooked caller;

    @Override
    public void setCaller(OwnHooked caller) {
        this.caller = caller;
    }

    public void onOwnHookedInstantiated() {
        caller.setOrigin(caller.getOrigin() == null ? "own" : caller.getOrigin() + ",own");
    }
}
