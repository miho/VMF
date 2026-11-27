package eu.mihosoft.vmf.conformance.executabledefaults;

import java.util.ArrayList;
import java.util.List;

public final class ExecutableDefaults {
    private static final List<String> EVENTS = new ArrayList<>();
    private static int sequence;

    private ExecutableDefaults() { }

    public static String fallbackLabel() {
        EVENTS.add("label");
        return "prepared";
    }

    public static int computeQuota() {
        EVENTS.add("quota");
        return Math.multiplyExact(7, 4);
    }

    public static double scale() {
        EVENTS.add("scale");
        return Math.copySign(0.0, -1.0);
    }

    public static int hookPriority() {
        EVENTS.add("priority");
        return Math.addExact(40, 1);
    }

    public static String sample(String name) {
        String value = name + "-" + ++sequence;
        EVENTS.add(value);
        return value;
    }

    static void restartSequence() {
        sequence = 0;
    }

    static void observeHook(String label, int priority) {
        EVENTS.add("hook:" + label + "/" + priority);
    }

    static void observeJoinedHook(String label, String alias) {
        EVENTS.add("joined-hook:" + label + "/" + alias);
    }

    static List<String> takeEvents() {
        var result = List.copyOf(EVENTS);
        EVENTS.clear();
        return result;
    }
}
