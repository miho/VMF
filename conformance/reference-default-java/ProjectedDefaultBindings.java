package eu.mihosoft.vmf.conformance.referencedefaults;

import java.util.ArrayList;
import java.util.List;

public final class ProjectedDefaultBindings {
    static final List<String> events = new ArrayList<>();
    private ProjectedDefaultBindings() { }

    public static String accent() { return text("accent"); }
    public static String ahead() { return text("ahead"); }
    public static String before() { return text("before"); }
    public static String path() { return text("path"); }
    public static String zulu() { return text("zulu"); }

    public static List<Integer> marks() {
        events.add("marks");
        return List.of(6);
    }

    public static List<Integer> prunes() {
        events.add("prunes");
        return List.of(7);
    }

    public static List<Integer> numbers() {
        events.add("numbers");
        return List.of(2, 3);
    }

    public static List<Integer> summary() {
        events.add("summary");
        return List.of(4, 5);
    }

    public static List<SelectionTarget> favorites() {
        events.add("favorites");
        return List.of(ReferenceDefaultBindings.target("favorite"));
    }

    public static List<SelectionTarget> queue() {
        events.add("queue");
        return List.of(ReferenceDefaultBindings.target("first"), ReferenceDefaultBindings.target("second"));
    }

    private static String text(String key) {
        events.add(key);
        return key;
    }
}
