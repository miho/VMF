package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.List;
import java.util.Map;

final class InheritedMixedDefaultsConformance {
    private InheritedMixedDefaultsConformance() { }

    static void verify() {
        InheritedMixedDefaultBindings.events.clear();
        var base = ExecutableSelectionBase.newInstance();
        calls("queue", "summary", "zulu");
        var owner = ExecutableSelectionLeaf.newInstance();
        calls("ahead", "options", "queue", "summary", "zulu");
        var queue = field(owner, "queue");
        var seed = owner.getQueue().get(0);
        var fallback = members(queue);
        check(fallback.get(0) == seed && members(field(owner.asReadOnly(), "queue")).get(0) == seed.asReadOnly(),
                "Inherited reflected defaults retain their identities through the read-only view");
        check(owner.getSummary().equals(List.of(4, 5)) && owner.getOptions().get("port") == 17,
                "Both managed collections use the inherited providers");

        field(owner, "summary").setDefault(List.of(13));
        owner.getSummary().clear();
        owner.getSummary().add(9);
        owner.getOptions().put("port", null);
        owner.setAhead("explicit");
        InheritedMixedDefaultBindings.events.clear();
        ExecutableSelectionLeaf deep = owner.vmf().content().deepCopy();
        calls("ahead", "options", "summary", "zulu");
        ExecutableSelectionLeaf shallow = owner.vmf().content().shallowCopy();
        calls("ahead", "options", "summary", "zulu");
        check(deep.getQueue().get(0) != seed && members(field(deep, "queue")).get(0) == deep.getQueue().get(0)
                        && shallow.getQueue().get(0) == seed && members(field(shallow, "queue")) == fallback,
                "Copy shells preserve the appropriate fallback identities");
        for (var copy : List.of(deep, shallow)) {
            check(copy.getAhead().equals("explicit") && copy.getSummary().equals(List.of(9))
                            && field(copy, "summary").getDefault().equals(List.of(13))
                            && copy.getOptions().containsKey("port") && copy.getOptions().get("port") == null,
                    "Copied leaf data and configured defaults remain independent");
        }
        calls();

        var draft = ExecutableSelectionLeaf.newBuilder().withAhead(null).withQueue(List.of()).withOptions(Map.of());
        calls("queue", "ahead", "options", "summary", "zulu");
        var first = draft.build();
        calls("ahead", "options", "summary", "zulu");
        var second = draft.build();
        calls("ahead", "options", "summary", "zulu");
        check(first.getAhead() == null && first.getQueue().isEmpty() && first.getOptions().isEmpty()
                        && members(field(first, "queue")) == members(field(second, "queue")),
                "Repeated builds preserve explicit values and use the staged cache");

        draft.appendCollections(false).applyFrom(base).applyTo(owner);
        calls();
        check(owner.getQueue().get(0) == base.getQueue().get(0) && owner.getSummary().equals(List.of(4, 5))
                        && owner.getAhead() == null && owner.getOptions().isEmpty() && members(queue) == fallback,
                "Import from the base leaves the staged child data and the target reset cache intact");
        queue.unset();
        check(owner.getQueue().get(0) == seed, "Reset returns the original fallback");
        calls();
        verifyWithoutScalarProvider();
        System.out.println("Java verified mixed inherited evaluator order, builders, copies and read-only fallback aliases.");
    }

    private static void verifyWithoutScalarProvider() {
        var model = ManagedSelectionLeaf.newInstance();
        calls("summary", "queue");
        var staged = ManagedSelectionLeaf.newBuilder();
        calls("queue", "summary");
        var built = staged.build();
        calls("summary");
        ManagedSelectionLeaf copy = model.vmf().content().deepCopy();
        calls("summary");
        check(built.getEarlier().equals(List.of(4, 5)) && copy.getQueue().get(0) != model.getQueue().get(0),
                "Managed providers alone also require effective constructor order");
    }

    private static Property field(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static List<?> members(Property field) {
        return (List<?>) field.getDefault();
    }

    private static void calls(String... expected) {
        check(InheritedMixedDefaultBindings.events.equals(List.of(expected)), "Provider order: " + InheritedMixedDefaultBindings.events);
        InheritedMixedDefaultBindings.events.clear();
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
