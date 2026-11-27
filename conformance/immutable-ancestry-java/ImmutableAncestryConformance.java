package eu.mihosoft.vmf.conformance.immutableancestry;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.immutableancestry.impl.BaseValueImpl;
import eu.mihosoft.vmf.conformance.immutableancestry.impl.InheritedValueImpl;
import eu.mihosoft.vmf.conformance.immutableancestry.impl.LeafValueImpl;
import eu.mihosoft.vmf.conformance.immutableancestry.impl.SpecialValueImpl;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.DelegatedBehavior;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ImmutableAncestryConformance {
    private static final String PACKAGE = "eu.mihosoft.vmf.conformance.immutableancestry";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(VMFJacksonModule
            .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(false));
    private static final List<String> BINDINGS = new ArrayList<>();
    private static int hooks;

    private ImmutableAncestryConformance() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>.");
        }
        var leaf = exercise();
        var graph = ValueEnvelope.newBuilder().withValue(leaf).build();
        String wire = MAPPER.writeValueAsString(graph);
        check(MAPPER.readTree(wire).path("value").path("@vmf-type").asText().equals(PACKAGE + ".LeafValue"),
                "The stable leaf discriminator is retained through a base-valued field");

        var directory = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("java-value.json"), wire);
        } else {
            String nativeWire = Files.readString(directory.resolve("csharp-value.json"));
            check(MAPPER.readTree(wire).equals(MAPPER.readTree(nativeWire)), "C# emits the same model data");
            int before = BINDINGS.size();
            var restored = MAPPER.readValue(nativeWire, ValueEnvelope.class);
            check(restored.getValue() instanceof LeafValue && BINDINGS.size() == before,
                    "Reload preserves the concrete type with a fresh lazy binding");
            check(((LeafValueImpl) restored.getValue()).score(2) == 60
                    && BINDINGS.get(before).equals("special:LeafValue"), "Reload selects the concrete override");
        }
        ImmutableDataBasesExchange.verify(directory, args[0].equals("write"));
        ImmutablePortSettingsExchange.verify(directory, args[0].equals("write"));
        ImmutableParentOrderExchange.verify(directory, args[0].equals("write"));
        System.out.println("Java verified immutable behavior inheritance, composed data bases and snapshot interchange.");
    }

    private static LeafValue exercise() {
        var base = BaseValue.newBuilder().withBase(3).build();
        var inherited = InheritedValue.newBuilder().withBase(5).withName("inherited").build();
        var special = SpecialValue.newBuilder().withBase(7).withFactor(4).build();
        var leaf = LeafValue.newBuilder().withBase(8).withFactor(6).withLabel("leaf").build();
        check(BINDINGS.isEmpty() && hooks == 0, "Immutable construction stays lazy");
        check(!BaseValueImpl.class.isInstance(inherited) && !BaseValueImpl.class.isInstance(special)
                && !SpecialValueImpl.class.isInstance(leaf) && BaseValue.class.isInstance(leaf),
                "Java value interfaces inherit across independent implementation classes");

        var baseCalls = (BaseValueImpl) base;
        var inheritedCalls = (InheritedValueImpl) inherited;
        var specialCalls = (SpecialValueImpl) special;
        var leafCalls = (LeafValueImpl) leaf;
        check(baseCalls.score(2) == 5 && baseCalls.render("base:").equals("base:3"),
                "Methods using one Java delegate reuse that binding");
        check(inheritedCalls.score(2) == 7 && inheritedCalls.render("inherited:").equals("inherited:5"),
                "Inherited calls retain the base delegate with the concrete caller");
        check(specialCalls.score(2) == 36 && specialCalls.adjust(3) == 12, "The override and added method share a binding");
        check(BINDINGS.equals(List.of("base:BaseValue", "base:InheritedValue", "special:SpecialValue")),
                "The first derived call leaves the inherited binding lazy");
        check(specialCalls.render("special:").equals("special:7"), "The inherited method initializes its own binding");
        check(leafCalls.score(2) == 60 && leafCalls.adjust(3) == 18 && leafCalls.render("leaf:").equals("leaf:8"),
                "A grandchild dispatches to the inherited effective override");
        check(BINDINGS.equals(List.of("base:BaseValue", "base:InheritedValue", "special:SpecialValue",
                "base:SpecialValue", "special:LeafValue", "base:LeafValue")) && hooks == 0, "Bindings remain per instance");
        check(Arrays.stream(LeafValue.class.getMethods()).noneMatch(method -> method.getName().equals("score"))
                && leaf.asReadOnly() == leaf && leaf.clone() == leaf, "The nominal public value exposes data");
        return leaf;
    }

    private static String kind(BaseValue model) {
        return model.vmf().reflect().type().getName().substring(PACKAGE.length() + 1);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class BaseBinding implements DelegatedBehavior<BaseValue> {
        private BaseValue owner;

        @Override public void setCaller(BaseValue caller) {
            owner = caller;
            BINDINGS.add("base:" + kind(caller));
        }

        public void onBaseValueInstantiated() { hooks++; }
        public int score(int increment) { return owner.getBase() + increment; }
        public String render(String prefix) { return prefix + owner.getBase(); }
    }

    public static final class SpecialBinding implements DelegatedBehavior<SpecialValue> {
        private SpecialValue owner;

        @Override public void setCaller(SpecialValue caller) {
            owner = caller;
            BINDINGS.add("special:" + kind(caller));
        }

        public void onSpecialValueInstantiated() { hooks++; }
        public int score(int increment) { return (owner.getBase() + increment) * owner.getFactor(); }
        public int adjust(int value) { return value * owner.getFactor(); }
    }
}
