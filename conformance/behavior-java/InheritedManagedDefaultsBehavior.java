package eu.mihosoft.vmf.conformance.behavior;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class InheritedManagedDefaultsBehavior implements DelegatedBehavior<InheritedManagedFields> {
    static final List<String> phases = new ArrayList<>();
    static final List<String> numbersEdits = new ArrayList<>();
    static final List<String> settingsEdits = new ArrayList<>();
    static InheritedManagedFields lastOwner;
    static Object lastNumbers;
    static Object lastSettings;
    private static int count;
    private InheritedManagedFields caller;

    static void reset() {
        phases.clear();
        numbersEdits.clear();
        settingsEdits.clear();
        lastOwner = null;
        lastNumbers = null;
        lastSettings = null;
        count = 0;
    }

    public static List<Integer> numbers() {
        phases.add("numbers:" + ++count);
        return List.of(count);
    }

    public static Map<String, Integer> settings() {
        phases.add("settings:" + count);
        return Map.of("saved-" + count, count);
    }

    public static List<Integer> replacementNumbers() {
        phases.add("alternative-numbers:" + ++count);
        return List.of(count + 10);
    }

    public static Map<String, Integer> replacementSettings() {
        phases.add("alternative-settings:" + count);
        return Map.of("altered-" + count, count + 10);
    }

    @Override public void setCaller(InheritedManagedFields caller) { this.caller = caller; }

    public void onInheritedManagedValuesInstantiated() { seed("values"); }
    public void onInheritedManagedReferencesInstantiated() { seed("references"); }

    public void onInheritedManagedOverriddenInstantiated() { seed("replacement"); }

    private void seed(String kind) {
        var numbers = caller.getNumbers();
        var settings = caller.getSettings();
        if (!numbers.isEmpty() || !settings.isEmpty()) throw new AssertionError("The hook must run before declared defaults");
        lastOwner = caller;
        lastNumbers = numbers;
        lastSettings = settings;
        phases.add("hook:" + kind);
        numbers.add(99);
        settings.put("seed-a", 1);
        settings.put("seed-b", 2);
        numbers.addChangeListener(change -> numbersEdits.add(change.removed().elements() + ">" + change.added().elements()));
        settings.addChangeListener(change -> settingsEdits.add(String.join(",", change.removed().entries().keySet())
                + ">" + String.join(",", change.added().entries().keySet())));
    }
}
