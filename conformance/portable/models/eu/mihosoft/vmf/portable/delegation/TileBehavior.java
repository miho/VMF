package eu.mihosoft.vmf.portable.delegation;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

/** The behavior of Tile.describe, which Tile binds again. */
public class TileBehavior implements DelegatedBehavior<Tile> {
    private Tile caller;

    @Override
    public void setCaller(Tile caller) {
        this.caller = caller;
    }

    public String describe() {
        return "tile " + caller.getName();
    }
}
