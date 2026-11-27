package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.AbstractRoleBehaviorExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.CovariantRoleOracle;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.Engine;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.EngineBehavior;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.EngineFleet;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.FastBehavior;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.FastEngine;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.InheritedEngine;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.IntLookup;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.Lookup;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.InheritedEqualityExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.LocalBindingExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.QualifiedBindingExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.MutableEqualityExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.MutableToStringExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.NestedRoleExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.ReadOnlyLookup;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.RepeatedRoleOracle;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.RoleContestExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.SharedRoleExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.SiblingRoleOracle;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.TrioRoleExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.DeepRoleOracle;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.PrimarySpineExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.SecondaryBehaviorExchange;
import eu.mihosoft.vmf.conformance.behaviorhierarchy.StockVFlowExchange;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

final class BehaviorHierarchyConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));

    private BehaviorHierarchyConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            var graph = exercise();
            Path file = exchange.resolve("java-behavior-hierarchy.json");
            MAPPER.writeValue(file.toFile(), graph);
            verifyWire(file);
            SecondaryBehaviorExchange.verify(exchange, true);
            AbstractRoleBehaviorExchange.verify(exchange, true);
            PrimarySpineExchange.verify(exchange, true);
            SharedRoleExchange.verify(exchange, true);
            DeepRoleOracle.verify(exchange, true);
            TrioRoleExchange.verify(exchange, true);
            LocalBindingExchange.verify(exchange, true);
            QualifiedBindingExchange.verify(exchange, true);
            NestedRoleExchange.verify(exchange, true);
            RepeatedRoleOracle.verify(exchange, true);
            SiblingRoleOracle.verify(exchange, true);
            CovariantRoleOracle.verify(exchange, true);
            RoleContestExchange.verify(exchange, true);
            StockVFlowExchange.verify(exchange, true);
            MutableToStringExchange.verify(exchange, true);
            MutableEqualityExchange.verify(exchange, true);
            InheritedEqualityExchange.verify(exchange, true);
            System.out.println("Java verified inherited constructor hooks, behavior dispatch, and closed generic callables.");
        } else {
            verifyWire(exchange.resolve("csharp-behavior-hierarchy.json"));
            SecondaryBehaviorExchange.verify(exchange, false);
            AbstractRoleBehaviorExchange.verify(exchange, false);
            PrimarySpineExchange.verify(exchange, false);
            SharedRoleExchange.verify(exchange, false);
            DeepRoleOracle.verify(exchange, false);
            TrioRoleExchange.verify(exchange, false);
            LocalBindingExchange.verify(exchange, false);
            QualifiedBindingExchange.verify(exchange, false);
            NestedRoleExchange.verify(exchange, false);
            RepeatedRoleOracle.verify(exchange, false);
            SiblingRoleOracle.verify(exchange, false);
            CovariantRoleOracle.verify(exchange, false);
            RoleContestExchange.verify(exchange, false);
            StockVFlowExchange.verify(exchange, false);
            MutableToStringExchange.verify(exchange, false);
            MutableEqualityExchange.verify(exchange, false);
            InheritedEqualityExchange.verify(exchange, false);
            System.out.println("Java restored the C# behavior hierarchy with fresh default bindings.");
        }
    }

    private static EngineFleet exercise() {
        var base = Engine.newBuilder().withPower(12).build();
        var inherited = InheritedEngine.newBuilder().withPower(13).withRate(15).build();
        var fast = FastEngine.newBuilder().withPower(14).withBonus(40).build();
        check(EngineBehavior.HOOK_VALUES.equals(List.of("base:0", "inherited:0:0"))
                && FastBehavior.HOOK_VALUES.equals(List.of("0:0")) && EngineBehavior.instances == 2,
                "Most-specific or inherited hook runs once before all base and subtype property values");
        Engine polymorphic = fast;
        check(base.transform(2) == 14 && inherited.transform(2) == 15 && polymorphic.transform(2) == 56,
                "Overridden and inherited delegated signatures dispatch through the runtime subtype");
        check("base:14".equals(fast.label("base")) && EngineBehavior.instances == 3 && EngineBehavior.HOOK_VALUES.size() == 2,
                "Inherited base method lazily constructs its own Java delegate without rerunning the displaced hook");
        rejectBaseReplacement(polymorphic);
        polymorphic.vmf().behavior().set(new FastReplacement());
        check(polymorphic.transform(2) == 102 && "x:override".equals(fast.label("x")) && base.transform(2) == 14,
                "A runtime subtype binding covers both inherited and overridden signatures on a single instance");

        var lookup = IntLookup.newBuilder().withValue(5).build();
        Lookup<Integer> typed = lookup;
        check(typed.metric(8) == 13 && Arrays.stream(ReadOnlyLookup.class.getMethods())
                .noneMatch(method -> method.getName().equals("metric")), "A named closed generic subtype exposes data and its scalar callable");

        var graph = EngineFleet.newBuilder().withEngines(base, inherited, fast).withLookup(lookup).build();
        var copied = (EngineFleet) graph.vmf().content().deepCopy();
        verifyRestored(copied);
        check(copied != graph && copied.getEngines().get(2) != fast && fast.transform(2) == 102,
                "An independent graph copy rebinds defaults and preserves the original replacement");
        return graph;
    }

    private static void rejectBaseReplacement(Engine engine) {
        try {
            engine.vmf().behavior().set(new BaseReplacement());
        } catch (RuntimeException expected) {
            check(expected.getMessage().contains("FastEngine.Behavior"), "Replacement requires the exact subtype contract");
            return;
        }
        throw new AssertionError("A base-only replacement must be rejected on a derived model");
    }

    private static void verifyWire(Path file) throws IOException {
        var document = MAPPER.readTree(file.toFile());
        var tags = document.findValuesAsText("@vmf-type");
        check(tags.contains(InheritedEngine.class.getName()) && tags.contains(FastEngine.class.getName())
                && document.findValue("behavior") == null && document.findValue("behaviorOverride") == null,
                "Polymorphic snapshot retains subtype identifiers and ordinary data");
        verifyRestored(MAPPER.readValue(file.toFile(), EngineFleet.class));
    }

    private static void verifyRestored(EngineFleet graph) {
        var engines = graph.getEngines();
        check(engines.size() == 3 && !(engines.get(0) instanceof InheritedEngine)
                && !(engines.get(0) instanceof FastEngine) && engines.get(1) instanceof InheritedEngine
                && engines.get(2) instanceof FastEngine, "Polymorphic inheritance restores in ordered containment");
        var inherited = (InheritedEngine) engines.get(1);
        var fast = (FastEngine) engines.get(2);
        check(inherited.getRate() == 15 && fast.getBonus() == 40 && engines.get(0).transform(2) == 14
                && inherited.transform(2) == 15 && fast.transform(2) == 56 && "base:14".equals(fast.label("base"))
                && engines.stream().allMatch(engine -> engine.vmf().behavior().get() == null),
                "Restored models dispatch to defaults while retaining inherited and own data");
        check(graph.getLookup().getValue() == 5 && graph.getLookup().metric(8) == 13,
                "Closed generic data and callable survive the round trip");
    }

    private static class BaseReplacement implements Engine.Behavior {
        @Override public int transform(int delta) { return 100 + delta; }
        @Override public String label(String prefix) { return prefix + ":override"; }
    }

    private static final class FastReplacement extends BaseReplacement implements FastEngine.Behavior { }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
