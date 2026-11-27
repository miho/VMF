package eu.mihosoft.vmf.conformance.referencedefaults;

import java.util.ArrayList;
import java.util.List;

public final class HookReferenceDefaultBindings {
    static final List<String> events = new ArrayList<>();

    public static List<SelectionTarget> queue() {
        events.add("queue");
        return List.of(ReferenceDefaultBindings.target("seed"));
    }

    public static String zulu() {
        events.add("zulu");
        return "last";
    }

    public static List<Integer> earlier() {
        events.add("earlier");
        return List.of(4);
    }
}
