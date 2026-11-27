package eu.mihosoft.vmf.conformance.referencedefaults;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MixedDefaultBindings {
    static final List<String> events = new ArrayList<>();
    static boolean duplicateSeed;

    private MixedDefaultBindings() { }

    public static String caption() {
        events.add("caption");
        return "fallback";
    }

    public static List<SelectionTarget> favorites() {
        events.add("favorites");
        var seed = SelectionTarget.newBuilder().withCode("seed").build();
        return duplicateSeed ? List.of(seed, seed) : List.of(seed);
    }

    public static List<Integer> numbers() {
        events.add("numbers");
        return List.of(3, 5);
    }

    public static Map<String, Integer> options() {
        events.add("options");
        return Map.of("port", 7);
    }

    public static List<SelectionToken> tokens() {
        events.add("tokens");
        return List.of(SelectionToken.newBuilder().withKey("token").withLabel("label").build());
    }
}
