package eu.mihosoft.vmf.portable.delegation;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

/** The construction hook of FirstHooked: appends "first" to origin, so that a second hook would show. */
public class FirstHook implements DelegatedBehavior<FirstHooked> {
    private FirstHooked caller;

    @Override
    public void setCaller(FirstHooked caller) {
        this.caller = caller;
    }

    public void onFirstHookedInstantiated() {
        caller.setOrigin(caller.getOrigin() == null ? "first" : caller.getOrigin() + ",first");
    }

    public String firstRole() {
        return "first role";
    }
}
