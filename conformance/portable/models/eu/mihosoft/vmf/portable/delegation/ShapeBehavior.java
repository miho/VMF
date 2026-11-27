package eu.mihosoft.vmf.portable.delegation;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

/** The behavior of Shape.describe for the subtypes that do not bind it again. */
public class ShapeBehavior implements DelegatedBehavior<Shape> {
    private Shape caller;

    @Override
    public void setCaller(Shape caller) {
        this.caller = caller;
    }

    public String describe() {
        return "shape " + caller.getName();
    }
}
