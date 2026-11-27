package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.List;

final class HookReferenceDefaultsConformance {
    private HookReferenceDefaultsConformance() { }

    static void verify() {
        HookReferenceDefaultBindings.events.clear();
        check(HookSelectionBase.newInstance().getQueue().size() == 1, "The unhooked base captures once");
        calls("queue", "zulu");
        initialized(HookSelection.newInstance());
        calls("hook", "earlier", "queue", "zulu");
        var leaf = HookSelectionLeaf.newInstance();
        initialized(leaf);
        calls("hook", "earlier", "queue", "zulu");

        var staged = HookSelectionLeaf.newBuilder();
        calls("queue", "earlier", "zulu");
        var first = staged.build();
        initialized(first);
        calls("hook", "earlier", "zulu");
        var empty = staged.withQueue(List.of()).build();
        calls("hook", "earlier", "zulu");
        check(empty.getQueue().isEmpty() && field(empty, "queue").isSet() && fallback(first) == fallback(empty),
                "Explicit emptiness replaces hook membership and retains the staged cache");
        field(empty, "queue").unset();
        check(empty.getQueue().get(0) == first.getQueue().get(0), "Reset restores the staged identity");
        calls();

        HookSelectionLeaf deep = leaf.vmf().content().deepCopy();
        calls("hook", "earlier", "zulu");
        HookSelectionLeaf shallow = leaf.vmf().content().shallowCopy();
        calls("hook", "earlier", "zulu");
        for (var copy : List.of(deep, shallow)) {
            check(copy.getQueue().size() == 2 && copy.getQueue().get(0).getCode().equals("hook-member")
                            && fallback(copy).get(0) == copy.getQueue().get(1) && field(copy, "queue").isSet(),
                    "Copies retain the hook's new member before the source membership");
        }
        check(deep.getQueue().get(1) != leaf.getQueue().get(0)
                        && shallow.getQueue().get(1) == leaf.getQueue().get(0)
                        && fallback(shallow) == fallback(leaf),
                "Deep copies remap fallback aliases and shallow copies preserve the source aliases");
        calls();
        System.out.println("Java verified inherited hook lifecycle and reference-default fallback identities.");
    }

    private static void initialized(HookSelection owner) {
        var seed = owner.getQueue().get(0);
        check(owner.getQueue().size() == 1 && seed.getCode().equals("seed") && fallback(owner).get(0) == seed
                        && owner.getEarlier().equals(List.of(4)) && "last".equals(owner.getZulu())
                        && !field(owner, "queue").isSet()
                        && ((List<?>) field(owner.asReadOnly(), "queue").getDefault()).get(0) == seed.asReadOnly(),
                "Construction replaces hook edits and publishes the fallback through both views");
    }

    private static List<?> fallback(HookSelection owner) {
        return (List<?>) field(owner, "queue").getDefault();
    }

    static Property field(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static void calls(String... expected) {
        check(HookReferenceDefaultBindings.events.equals(List.of(expected)),
                "Hook and provider order: " + HookReferenceDefaultBindings.events);
        HookReferenceDefaultBindings.events.clear();
    }

    static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
