package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.List;

final class ComposedReferenceDefaultsConformance {
    private ComposedReferenceDefaultsConformance() { }

    static void verify() {
        var owner = ComposedDefaultSelectionLeaf.newInstance();
        calls("ahead", "queue", "summary", "zulu");
        PassiveConcreteRole role = owner;
        DefaultPrimary root = owner;
        check("north".equals(role.getRegion()) && "early".equals(owner.getAhead()) && owner.getSummary().equals(List.of(4, 5))
                && defaults(root).get(0) == owner.getQueue().get(0)
                && defaults(owner.asReadOnly()).get(0) == owner.getQueue().get(0).asReadOnly(), "Primary and companion aliases see the constructed defaults.");
        role.setRegion("south");
        var builder = ComposedDefaultSelectionLeaf.newBuilder();
        calls("queue", "ahead", "summary", "zulu");
        var built = builder.build();
        calls("ahead", "summary", "zulu");
        var empty = builder.withQueue(List.of()).withRank(18).build();
        calls("ahead", "summary", "zulu");
        check(empty.getQueue().isEmpty() && defaults(empty) == defaults(built) && empty.getRank() == 18,
                "Ordinary secondary setters and explicit empty builder membership are preserved.");
        ComposedDefaultSelectionLeaf deep = owner.vmf().content().deepCopy();
        calls("ahead", "summary", "zulu");
        ComposedDefaultSelectionLeaf shallow = owner.vmf().content().shallowCopy();
        calls("ahead", "summary", "zulu");
        check("south".equals(deep.getRegion()) && "south".equals(shallow.getRegion()), "Copies preserve secondary data.");
        check(defaults(deep).get(0) == deep.getQueue().get(0) && deep.getQueue().get(0) != owner.getQueue().get(0)
                && defaults(shallow) == defaults(root) && shallow.getQueue().get(0) == owner.getQueue().get(0),
                "Copying follows the default members and graph identity.");
        System.out.println("Java verified reference defaults with a passive concrete secondary data role.");
    }

    private static List<?> defaults(VObject owner) {
        return (List<?>) owner.vmf().reflect().propertyByName("queue").orElseThrow().getDefault();
    }

    private static void calls(String... expected) {
        check(InheritedMixedDefaultBindings.events.equals(List.of(expected)), "Providers called: " + InheritedMixedDefaultBindings.events);
        InheritedMixedDefaultBindings.events.clear();
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
