package eu.mihosoft.vmf.conformance.referencedefaults;

import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public final class RootHookDefaultsConformance {
    private RootHookDefaultsConformance() { }

    public static void main(String[] args) {
        verify();
    }

    public static String ahead() {
        HookReferenceDefaultBindings.events.add("ahead");
        return "early";
    }

    static void verify() {
        HookReferenceDefaultBindings.events.clear();
        var root = RootHookSelection.newInstance();
        calls("hook-root", "earlier", "queue", "zulu");
        initialized(root, root.asReadOnly(), root.getQueue().size(), root.getQueue().get(0), root.getEarlier(), root.getZulu());

        var leaf = RootHookSelectionLeaf.newInstance();
        calls("hook-leaf", "ahead", "earlier", "queue", "zulu");
        initialized(leaf, leaf.asReadOnly(), leaf.getQueue().size(), leaf.getQueue().get(0), leaf.getEarlier(), leaf.getZulu());
        check("early".equals(leaf.getAhead()), "Leaf default replaces the inherited hook edit");
        var builder = RootHookSelectionLeaf.newBuilder();
        calls("queue", "ahead", "earlier", "zulu");
        var built = builder.build();
        calls("hook-leaf", "ahead", "earlier", "zulu");
        initialized(built, built.asReadOnly(), built.getQueue().size(), built.getQueue().get(0), built.getEarlier(), built.getZulu());
        var empty = builder.withQueue(List.of()).build();
        calls("hook-leaf", "ahead", "earlier", "zulu");
        check(empty.getQueue().isEmpty() && fallback(empty) == fallback(built), "Leaf builds reuse the staged cache");
        RootHookSelectionLeaf deep = leaf.vmf().content().deepCopy();
        calls("hook-leaf", "ahead", "earlier", "zulu");
        RootHookSelectionLeaf shallow = leaf.vmf().content().shallowCopy();
        calls("hook-leaf", "ahead", "earlier", "zulu");
        check(deep.getQueue().stream().map(SelectionTarget::getCode).collect(Collectors.toList())
                        .equals(List.of("hook-member", "seed"))
                        && deep.getQueue().get(1) != leaf.getQueue().get(0)
                        && fallback(deep).get(0) == deep.getQueue().get(1)
                        && shallow.getQueue().get(1) == leaf.getQueue().get(0) && fallback(shallow) == fallback(leaf),
                "Copies of the leaf retain hook additions and remap or share the source fallback");

        var overriding = RootHookOverride.newInstance();
        calls("hook-child-local", "earlier", "queue", "zulu");
        initialized(overriding, overriding.asReadOnly(), overriding.getQueue().size(), overriding.getQueue().get(0),
                overriding.getEarlier(), overriding.getZulu());
        var overridingBuilder = RootHookOverride.newBuilder();
        calls("queue", "earlier", "zulu");
        overridingBuilder.build();
        calls("hook-child-local", "earlier", "zulu");
        RootHookOverride overrideCopy = overriding.vmf().content().deepCopy();
        calls("hook-child-local", "earlier", "zulu");
        check(overrideCopy.getQueue().get(0).getCode().equals("hook-member")
                        && overrideCopy.getQueue().get(1) == fallback(overrideCopy).get(0),
                "The closest declared hook replaces an ancestor hook in copy shells too");

        var single = SingleHookSelection.newInstance();
        calls("hook-single", "earlier", "queue", "zulu");
        initialized(single, single.asReadOnly(), single.getQueue().size(), single.getQueue().iterator().next(), single.getEarlier(), single.getZulu());
        var singleBuilder = SingleHookSelection.newBuilder();
        calls("queue", "earlier", "zulu");
        var singleBuilt = singleBuilder.build();
        calls("hook-single", "earlier", "zulu");
        initialized(singleBuilt, singleBuilt.asReadOnly(), singleBuilt.getQueue().size(), singleBuilt.getQueue().iterator().next(), singleBuilt.getEarlier(), singleBuilt.getZulu());
        var singleEmpty = singleBuilder.withQueue(List.of()).build();
        calls("hook-single", "earlier", "zulu");
        check(singleEmpty.getQueue().isEmpty() && fallback(singleEmpty) == fallback(singleBuilt), "Standalone builds reuse the staged cache");
        SingleHookSelection deepSingle = single.vmf().content().deepCopy();
        calls("hook-single", "earlier", "zulu");
        SingleHookSelection shallowSingle = single.vmf().content().shallowCopy();
        calls("hook-single", "earlier", "zulu");
        check(deepSingle.getQueue().size() == 2 && shallowSingle.getQueue().size() == 2
                        && !deepSingle.getQueue().contains(single.getQueue().iterator().next())
                        && deepSingle.getQueue().contains((SelectionTarget) fallback(deepSingle).get(0))
                        && shallowSingle.getQueue().contains(single.getQueue().iterator().next())
                        && fallback(single) == fallback(shallowSingle),
                "Unordered standalone copies preserve hook members and follow copy identity rules");
        verifyBuilderTransfer();
        System.out.println("Java verified root-declared and standalone hook construction, builders, and copies.");
    }

    private static void verifyBuilderTransfer() {
        var source = RootHookSelection.newInstance();
        calls("hook-root", "earlier", "queue", "zulu");
        var imported = SelectionTarget.newBuilder().withCode("imported").build();
        source.getQueue().clear();
        source.getQueue().add(imported);
        source.getEarlier().clear();
        source.getEarlier().add(8);
        source.setZulu(null);

        var builder = RootHookSelectionLeaf.newBuilder();
        calls("queue", "ahead", "earlier", "zulu");
        builder.withAhead("leaf-only").applyFrom(source);
        calls();
        var built = builder.build();
        calls("hook-leaf", "ahead", "earlier", "zulu");
        check(built.getEarlier().equals(List.of(4, 8)) && "leaf-only".equals(built.getAhead())
                        && built.getZulu() == null && built.getQueue().size() == 2
                        && built.getQueue().get(0) == fallback(built).get(0) && built.getQueue().get(1) == imported,
                "Appending an ancestor model retains builder defaults and stages its current fields by identity");

        var target = RootHookSelectionLeaf.newInstance();
        calls("hook-leaf", "ahead", "earlier", "queue", "zulu");
        var targetSeed = target.getQueue().get(0);
        builder.applyTo(target);
        calls();
        check(target.getEarlier().equals(List.of(4, 4, 8)) && target.getQueue().size() == 3
                        && target.getQueue().get(0) == targetSeed && target.getQueue().get(1) == built.getQueue().get(0)
                        && target.getQueue().get(2) == imported && fallback(target).get(0) == targetSeed
                        && "leaf-only".equals(target.getAhead()) && target.getZulu() == null,
                "Applying an append transfer preserves the target fallback and does not run the hook");

        builder.appendCollections(false).applyFrom(source).applyTo(target);
        calls();
        var replaced = builder.build();
        calls("hook-leaf", "ahead", "earlier", "zulu");
        check(replaced.getEarlier().equals(List.of(8)) && target.getEarlier().equals(List.of(8)),
                "Replacement applies imported scalar collection contents");
        check(replaced.getQueue().size() == 1 && replaced.getQueue().get(0) == imported
                        && target.getQueue().size() == 1 && target.getQueue().get(0) == imported,
                "Replacement stages and applies the imported reference");
        check(fallback(replaced) == fallback(built) && fallback(target).get(0) == targetSeed
                        && "leaf-only".equals(replaced.getAhead()),
                "Replacement preserves the default identities and leaf-only field");
    }

    private static void initialized(VObject owner, VObject view, int size, SelectionTarget seed,
                                    Collection<Integer> earlier, String zulu) {
        check(size == 1 && "seed".equals(seed.getCode()) && fallback(owner).get(0) == seed
                        && fallback(view).get(0) == seed.asReadOnly() && earlier.equals(List.of(4)) && "last".equals(zulu),
                "The declared defaults replace constructor hook edits");
    }

    static void beforeHook(VObject owner, VObject view, int queueSize, int earlierSize, String zulu, String event) {
        HookReferenceDefaultBindings.events.add(event);
        var mutable = HookReferenceDefaultsConformance.field(owner, "queue");
        var readOnly = HookReferenceDefaultsConformance.field(view, "queue");
        check(queueSize == 0 && earlierSize == 0 && zulu == null && mutable.getDefault() == null && mutable.isSet(),
                "The hook runs on initialized collections before fallback capture");
        unavailable(readOnly::getDefault);
        unavailable(readOnly::isSet);
    }

    private static void unavailable(Runnable read) {
        try {
            read.run();
        } catch (NullPointerException expected) {
            return;
        }
        throw new AssertionError("The read-only reference fallback is unavailable during the hook");
    }

    private static List<?> fallback(VObject owner) {
        return (List<?>) HookReferenceDefaultsConformance.field(owner, "queue").getDefault();
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
