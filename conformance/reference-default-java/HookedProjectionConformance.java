package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.List;
import java.util.stream.Collectors;

final class HookedProjectionConformance {
    private static final String[] SHELL_ORDER = {"hook-leaf", "accent", "ahead", "before", "numbers", "path", "summary", "zulu"};

    private HookedProjectionConformance() { }

    public static void main(String[] args) {
        verify();
    }

    static void verify() {
        ProjectedDefaultBindings.events.clear();
        var composed = HookedProjection.newInstance();
        calls("hook", "accent", "before", "favorites", "numbers", "path", "queue", "summary", "zulu");
        initialized(composed);
        check("direct:2:2:1:2:before".equals(composed.describe("direct")),
                "The direct call observes initialized data through the provider that ran in the hook.");
        var leaf = HookedProjectionLeaf.newInstance();
        calls("hook-leaf", "accent", "ahead", "before", "favorites", "numbers", "path", "queue", "summary", "zulu");
        initialized(leaf);
        check("ahead".equals(leaf.getAhead()), "The leaf default replaces the inherited hook edit.");
        HookedProjection baseView = leaf;
        check("leaf:2:2:1:2:before".equals(leaf.describe("leaf")) && "base:3:2:1:2:before".equals(baseView.describe("base")),
                "The base and concrete calls share the leaf's independent provider state.");

        var staged = HookedProjectionLeaf.newBuilder();
        calls("favorites", "queue", "accent", "ahead", "before", "numbers", "path", "summary", "zulu");
        var first = staged.build();
        calls(SHELL_ORDER);
        initialized(first);
        check("build:2:2:1:2:before".equals(first.describe("build")), "Each build has its own provider.");
        var empty = staged.withQueue(List.of()).withFavorites(List.of()).build();
        calls(SHELL_ORDER);
        check(empty.getQueue().isEmpty() && empty.getFavorites().isEmpty()
                && cache(empty, "queue") == cache(first, "queue")
                && cache(empty, "favorites") == cache(first, "favorites"),
                "A builder captures independent fallback membership and can override hook edits.");
        check("empty:2:0:0:2:before".equals(empty.describe("empty")), "A rebuilt owner uses its own provider and the staged data.");

        HookedProjectionLeaf deep = leaf.vmf().content().deepCopy();
        calls(SHELL_ORDER);
        HookedProjectionLeaf shallow = leaf.vmf().content().shallowCopy();
        calls(SHELL_ORDER);
        for (var copy : List.of(deep, shallow)) {
            check("copy:2:3:2:2:before".equals(copy.describe("copy")), "A copy creates a new provider for the new shell.");
            check(copy.getQueue().stream().map(SelectionTarget::getCode).collect(Collectors.toList())
                            .equals(List.of("hook-queue", "first", "second"))
                    && copy.getFavorites().size() == 2 && copy.getFavorites().contains((SelectionTarget) cache(copy, "favorites").get(0))
                    && cache(copy, "queue").get(0) == copy.getQueue().get(1),
                    "Copy shells append source members to hook-added members without replacing their fallback.");
        }
        check(deep.getQueue().get(1) != leaf.getQueue().get(0)
                && cache(deep, "favorites").get(0) != cache(leaf, "favorites").get(0)
                && shallow.getQueue().get(1) == leaf.getQueue().get(0)
                && cache(shallow, "favorites") == cache(leaf, "favorites")
                && cache(shallow, "queue") == cache(leaf, "queue"), "Deep and shallow cache alias rules.");
        check("source:4:2:1:2:before".equals(leaf.describe("source")), "Copy behavior leaves the source provider untouched.");
        System.out.println("Java verified hook and delegated instance state with projected executable and reference defaults.");
    }

    private static void initialized(HookedProjection owner) {
        var queue = owner.getQueue().get(0);
        var favorite = owner.getFavorites().iterator().next();
        check(owner.getQueue().size() == 2 && "first".equals(queue.getCode())
                && owner.getFavorites().size() == 1 && "favorite".equals(favorite.getCode())
                && owner.getNumbers().equals(List.of(2, 3)) && owner.getSummary().equals(List.of(4, 5))
                && "before".equals(owner.getBefore()) && "zulu".equals(owner.getZulu())
                && cache(owner, "queue").get(0) == queue && cache(owner, "favorites").get(0) == favorite
                && cache(owner.asReadOnly(), "queue").get(0) == queue.asReadOnly()
                && cache(owner.asReadOnly(), "favorites").get(0) == favorite.asReadOnly(),
                "Construction replaces hook edits and publishes each reference fallback through every view.");
    }

    static Property field(VObject owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static List<?> cache(VObject owner, String name) {
        return (List<?>) field(owner, name).getDefault();
    }

    private static void calls(String... expected) {
        check(ProjectedDefaultBindings.events.equals(List.of(expected)), "Providers called: " + ProjectedDefaultBindings.events);
        ProjectedDefaultBindings.events.clear();
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
}
