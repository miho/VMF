package eu.mihosoft.vmf.conformance.referencedefaults;

import java.util.ArrayList;
import java.util.List;

public final class InheritedReferenceDefaultBindings {
    static final List<String> events = new ArrayList<>();

    private InheritedReferenceDefaultBindings() { }

    public static List<SelectionTarget> queue() {
        events.add("queue");
        return List.of(ReferenceDefaultBindings.target("default-first"), ReferenceDefaultBindings.target("default-second"));
    }

    public static List<SelectionTarget> saved() {
        events.add("saved");
        return List.of(ReferenceDefaultBindings.target("default-saved"));
    }
}
