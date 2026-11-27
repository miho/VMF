package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.ArrayList;
import java.util.List;

public final class SnapshotLiteralDefaultBehavior implements DelegatedBehavior<HookedLiteralCollections> {
    static final String CONSTRUCTED_TRACE = "seed-a>|seed-b>|>saved";
    private final List<String> mapEdits = new ArrayList<>();
    private HookedLiteralCollections owner;

    @Override public void setCaller(HookedLiteralCollections caller) { owner = caller; }

    public void onHookedLiteralCollectionsInstantiated() {
        if (!owner.getNumbers().isEmpty() || !owner.getOrdered().isEmpty()
                || !owner.getFlags().isEmpty() || !owner.getValues().isEmpty()) {
            throw new AssertionError("Managed literal defaults are applied after the hook");
        }
        owner.getNumbers().add(1);
        owner.getOrdered().add("hook");
        owner.getFlags().add(1);
        owner.getValues().put("seed-a", 1);
        owner.getValues().put("seed-b", 2);
        owner.getValues().addChangeListener(edit -> mapEdits.add(String.join(",", edit.removed().entries().keySet())
                + ">" + String.join(",", edit.added().entries().keySet())));
    }

    public String observedMapEdits() { return String.join("|", mapEdits); }
}
