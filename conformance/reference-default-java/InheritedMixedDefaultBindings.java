package eu.mihosoft.vmf.conformance.referencedefaults;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class InheritedMixedDefaultBindings {
    static final List<String> events = new ArrayList<>();

    private InheritedMixedDefaultBindings() { }

    public static String ahead() {
        events.add("ahead");
        return "early";
    }

    public static Map<String, Integer> options() {
        events.add("options");
        return Map.of("port", 17);
    }

    public static List<SelectionTarget> queue() {
        events.add("queue");
        return List.of(ReferenceDefaultBindings.target("first"), ReferenceDefaultBindings.target("second"));
    }

    public static List<Integer> summary() {
        events.add("summary");
        return List.of(4, 5);
    }

    public static String zulu() {
        events.add("zulu");
        return "last";
    }
}
