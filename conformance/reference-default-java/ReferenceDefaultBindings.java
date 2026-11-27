package eu.mihosoft.vmf.conformance.referencedefaults;

import java.util.List;

public final class ReferenceDefaultBindings {
    static int mutableCalls;
    static int immutableCalls;
    static boolean duplicateSeed;

    private ReferenceDefaultBindings() { }

    public static List<SelectionTarget> favorites() {
        mutableCalls++;
        var seed = target("default-favorite");
        return duplicateSeed ? List.of(seed, seed) : List.of(seed);
    }

    public static List<SelectionTarget> featured() {
        mutableCalls++;
        return List.of(target("default-first"), target("default-second"));
    }

    public static List<SelectionToken> tokens() {
        immutableCalls++;
        return List.of(token("seed-first", "seed"), token("seed-second", "seed"));
    }

    static SelectionTarget target(String code) {
        return SelectionTarget.newBuilder().withCode(code).build();
    }

    static SelectionToken token(String key, String label) {
        return SelectionToken.newBuilder().withKey(key).withLabel(label).build();
    }
}
