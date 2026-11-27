package eu.mihosoft.vmf.jackson.test.executabledeserialization;

import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DefaultTrace implements DelegatedBehavior<VObject> {
    static final List<String> events = new ArrayList<>();
    private static int evaluations;
    private VObject caller;

    public static void reset() {
        evaluations = 0;
        events.clear();
    }

    public static List<String> trace() {
        return List.copyOf(events);
    }

    public static List<Integer> numbers() {
        events.add("numbers:" + ++evaluations);
        return List.of(evaluations);
    }

    public static Map<String, Integer> settings() {
        events.add("settings:" + evaluations);
        return Map.of("saved-" + evaluations, evaluations);
    }

    @Override
    public void setCaller(VObject caller) {
        this.caller = caller;
    }

    public void onHookedValuesInstantiated() {
        var value = (HookedValues) caller;
        seed(value.getNumbers(), value.getSettings());
    }

    public void onHookedReferencesInstantiated() {
        var value = (HookedReferences) caller;
        seed(value.getNumbers(), value.getSettings());
    }

    public void onHookedMetadataPortInstantiated() {
        events.add("port-hook");
    }

    private static void seed(List<Integer> numbers, Map<String, Integer> settings) {
        events.add("hook");
        numbers.add(99);
        settings.put("hook", 99);
    }
}
