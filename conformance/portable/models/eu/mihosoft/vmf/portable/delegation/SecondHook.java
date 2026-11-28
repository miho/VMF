package eu.mihosoft.vmf.portable.delegation;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

/** The construction hook of SecondHooked: appends "second" to origin, so that a second hook would show. */
public class SecondHook implements DelegatedBehavior<SecondHooked> {
    private SecondHooked caller;

    @Override
    public void setCaller(SecondHooked caller) {
        this.caller = caller;
    }

    public void onSecondHookedInstantiated() {
        caller.setOrigin(caller.getOrigin() == null ? "second" : caller.getOrigin() + ",second");
    }

    public String secondRole() {
        return "second role";
    }
}
