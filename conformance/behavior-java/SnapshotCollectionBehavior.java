package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.ArrayList;
import java.util.List;

public final class SnapshotCollectionBehavior implements DelegatedBehavior<HookedSnapshotCollections> {
    private HookedSnapshotCollections owner;
    private int invocations;
    private final List<String> mapEdits = new ArrayList<>();

    @Override public void setCaller(HookedSnapshotCollections caller) { owner = caller; }

    public void onHookedSnapshotCollectionsInstantiated() {
        owner.getNumbers().add(1);
        owner.getOrdered().add("seed");
        owner.getFlags().add(1);
        owner.getValues().put("seed-a", 1);
        owner.getValues().put("seed-b", 2);
        owner.getValues().addChangeListener(edit -> mapEdits.add(String.join(",", edit.removed().entries().keySet())
                + ">" + String.join(",", edit.added().entries().keySet())));
        owner.getLegacy().add("seed");
    }

    public int invocation() { return ++invocations; }
    public String observedMapEdits() { return String.join("|", mapEdits); }
}
