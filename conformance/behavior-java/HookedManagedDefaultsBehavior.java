package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class HookedManagedDefaultsBehavior implements DelegatedBehavior<HookedManagedDefaults> {
    static final List<String> phases = new ArrayList<>();
    static int count;
    static boolean throwOnRemoval;
    static HookedManagedDefaults lastOwner;
    private HookedManagedDefaults owner;
    private final List<String> edits = new ArrayList<>();

    static void reset() {
        phases.clear();
        count = 0;
        throwOnRemoval = false;
        lastOwner = null;
    }

    public static List<Integer> numbers() {
        phases.add("numbers:" + (++count));
        return List.of(count);
    }

    public static int token() {
        phases.add("token:" + count);
        return count * 100;
    }

    public static Map<String, Integer> values() {
        phases.add("values:" + count);
        return Map.of("saved-" + count, count);
    }

    @Override public void setCaller(HookedManagedDefaults caller) { owner = caller; }

    public void onHookedManagedDefaultsInstantiated() {
        lastOwner = owner;
        phases.add("hook:" + owner.getToken());
        owner.getNumbers().add(99);
        owner.getValues().put("seed-a", 1);
        owner.getValues().put("seed-b", 2);
        owner.getValues().addChangeListener(edit -> {
            var removed = edit.removed().entries().keySet();
            edits.add(String.join(",", removed) + ">" + String.join(",", edit.added().entries().keySet()));
            if (throwOnRemoval && removed.contains("seed-a")) {
                throw new IllegalStateException("stop after seed-a");
            }
        });
    }

    public String observedMapEdits() { return String.join("|", edits); }
}
