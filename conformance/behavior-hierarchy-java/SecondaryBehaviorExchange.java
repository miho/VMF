package eu.mihosoft.vmf.conformance.behaviorhierarchy;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public final class SecondaryBehaviorExchange {
    private SecondaryBehaviorExchange() { }

    public static void verify(Path exchange, boolean writing) throws IOException {
        PowerBehavior.hooks.clear();
        PowerBehavior.instances = 0;
        MeterBehavior.hooks.clear();
        MeterBehavior.instances = 0;
        SpecialMeterBehavior.hooks.clear();

        var independent = MeteredCell.newBuilder().build();
        var composed = ComposedCell.newBuilder().withEnergy(12).withWeight(9).withName("paired").build();
        var special = SpecialCell.newBuilder().withEnergy(14).withWeight(8).withName("special").build();
        var standalone = StandaloneMeter.newBuilder().withVoltage(17).withWeight(6).build();
        check(PowerBehavior.hooks.equals(List.of("composed:0:0")) && PowerBehavior.instances == 1,
                "The primary hook wins and sees all raw values: " + PowerBehavior.hooks + " / " + PowerBehavior.instances);
        check(MeterBehavior.hooks.equals(List.of("metered:0", "standalone:0:0")) && MeterBehavior.instances == 2,
                "No primary hook means the selected secondary hook owns the same delegate as its calls");
        check(SpecialMeterBehavior.hooks.equals(List.of("0:0")), "The local hook displaces both inherited hooks");

        MeteredCell inherited = composed;
        MeteredCell overridden = special;
        check(inherited.callerIs(inherited) && !inherited.callerIs(independent)
                && inherited.advance(2) == 12 && inherited.calls() == 1 && composed.advance(2) == 13,
                "Secondary calls share one lazy binding and its caller is the composed owner");
        check(overridden.advance(2) == 110 && overridden.calls() == 0 && overridden.callerIs(overridden),
                "The local override and inherited siblings use their respective default bindings");
        check(independent.calls() == 0 && standalone.advance(2) == 9 && standalone.calls() == 1
                && MeterBehavior.instances == 4 && MeterBehavior.hooks.size() == 2,
                "An eager hook and a lazy call each keep independent per-owner state");

        try {
            composed.vmf().behavior().set(new PrimaryReplacement());
            throw new AssertionError("A primary-only replacement cannot cover the secondary role");
        } catch (RuntimeException expected) {
            check(expected.getMessage().contains("ComposedCell.Behavior"), "A replacement covers the runtime model contract");
        }
        composed.vmf().behavior().set(new ComposedReplacement());
        check(inherited.advance(2) == 202 && inherited.calls() == 99 && inherited.callerIs(inherited)
                && composed.amplify(2) == 502 && standalone.calls() == 1,
                "One replacement covers both primary and secondary calls on only that owner");
        check(Arrays.stream(ReadOnlyMeteredCell.class.getMethods()).noneMatch(method -> method.getName().equals("advance")),
                "Read-only contracts still expose data only");

        var graph = SecondaryBehaviorFleet.newBuilder().withCells(composed, special)
                .withStandalone(standalone).withMirror(composed).build();
        for (boolean references : new boolean[]{true, false}) {
            var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                    .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withModelWireTypeAliases(SecondaryBehaviorFleet.class).withReferences(references));
            String suffix = references ? "-secondary-behavior-reference.json" : "-secondary-behavior-by-value.json";
            String expected = mapper.writeValueAsString(graph);
            String wire = expected;
            if (writing) {
                Files.writeString(exchange.resolve("java" + suffix), wire);
            } else {
                wire = Files.readString(exchange.resolve("csharp" + suffix));
                check(mapper.readTree(expected).equals(mapper.readTree(wire)), "Both targets write the same modeled data");
            }
            var restored = mapper.readValue(wire, SecondaryBehaviorFleet.class);
            var copy = (ComposedCell) restored.getCells().get(0);
            var specialCopy = (SpecialCell) restored.getCells().get(1);
            check(references ? copy == restored.getMirror() : restored.getMirror() == null,
                    "The reference-aware profile keeps the cross-role alias and typed-by-value omits it");
            check("paired".equals(copy.getName()) && copy.getWeight() == 9 && copy.calls() == 0
                    && copy.advance(2) == 12 && copy.amplify(2) == 24 && copy.callerIs(copy)
                    && copy.vmf().behavior().get() == null, "Restored defaults bind to a fresh owner");
            check("special".equals(specialCopy.getName()) && specialCopy.advance(2) == 110 && specialCopy.calls() == 0
                    && restored.getStandalone().getVoltage() == 17 && restored.getStandalone().calls() == 0,
                    "Both profiles restore the same concrete subtype and unaffected sibling operations");
        }
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static class PrimaryReplacement implements PoweredCell.Behavior {
        @Override public int amplify(int delta) { return 500 + delta; }
    }

    private static final class ComposedReplacement extends PrimaryReplacement implements ComposedCell.Behavior {
        private VObject caller;
        @Override public void setCaller(VObject caller) { this.caller = caller; }
        @Override public int advance(int delta) { return 200 + delta; }
        @Override public int calls() { return 99; }
        @Override public boolean callerIs(MeteredCell value) { return caller == value; }
    }
}
