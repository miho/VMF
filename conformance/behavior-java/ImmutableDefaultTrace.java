package eu.mihosoft.vmf.conformance.behavior;

import java.util.ArrayList;
import java.util.List;

public final class ImmutableDefaultTrace {
    static final List<String> phases = new ArrayList<>();
    static boolean nullAlpha;

    private ImmutableDefaultTrace() { }

    public static String alpha() {
        phases.add("alpha");
        return nullAlpha ? null : "alpha-default";
    }

    public static List<ImmutableDefaultMember> members() {
        phases.add("members");
        return List.of(ImmutableDefaultMember.newBuilder().withLabel("default-member").build());
    }

    public static String label() {
        phases.add("label");
        return "default-label";
    }

    public static String witness() {
        phases.add("witness");
        return "default-witness";
    }

    public static int zulu() {
        phases.add("zulu");
        return 19;
    }
}
