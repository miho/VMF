package eu.mihosoft.vmf.conformance.executabledefaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ExecutableDefaultConformance {
    private ExecutableDefaultConformance() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Expected no arguments.");
        mutableDefaults();
        immutableDefaults();
        hookTiming();
        documentValues();
        inheritedProviders();
        builderCopiesStagedScalarValues();
        System.out.println("Java verified executable mutable and immutable defaults and construction hook timing.");
    }

    private static void builderCopiesStagedScalarValues() {
        ExecutableDefaults.restartSequence();
        var source = JoinedDefaults.newInstance();
        var target = JoinedDefaults.newInstance();
        events("joined-hook:null/null", "alias-1", "base-2", "joined-hook:null/null", "alias-3", "base-4");
        var builder = JoinedDefaults.newBuilder();
        events("alias-5", "base-6");

        var applied = new ArrayList<String>();
        var observer = target.vmf().changes().addLocalListener(change -> applied.add(change.propertyName()));
        try {
            check(builder.applyTo(target) == builder && target.getAlias().equals("alias-5")
                    && target.getLabel().equals("base-6"), "ApplyTo uses the staged fallbacks");
            check(applied.equals(List.of("alias", "label")), "ApplyTo writes in effective property order");
            events();

            source.setAlias("imported");
            source.setLabel(null);
            check(builder.applyFrom(source) == builder, "ApplyFrom returns the same builder");
            source.setAlias("changed after import");
            builder.applyTo(target);
            check(target.getAlias().equals("imported") && target.getLabel() == null,
                    "ApplyFrom copies current scalar values including explicit null");
            events();
        } finally {
            observer.unsubscribe();
        }

        var built = builder.build();
        check(built.getAlias().equals("imported") && built.getLabel() == null,
                "Building evaluates fresh constructor defaults before applying the imported values");
        events("joined-hook:null/null", "alias-7", "base-8");
    }

    private static void mutableDefaults() {
        var direct = MutableDefaults.newInstance();
        check(Objects.equals(direct.getLabel(), "prepared") && direct.getQuota() == 28, "Direct mutable construction");
        events("label", "quota");
        var staged = MutableDefaults.newBuilder();
        events("label", "quota");
        var overridden = staged.withLabel(null).withQuota(73).build();
        check(overridden.getLabel() == null && overridden.getQuota() == 73, "Mutable builder values take precedence");
        events("label", "quota");

        var label = field(overridden, "label");
        var quota = field(overridden, "quota");
        check(label.isSet() && quota.isSet(), "Explicit values differ from the reflected defaults");
        check(Objects.equals(label.getDefault(), "prepared") && Objects.equals(quota.getDefault(), 28), "Reflected defaults");
        label.unset();
        quota.unset();
        check(Objects.equals(overridden.getLabel(), "prepared") && overridden.getQuota() == 28, "Mutable reset");
        events("label", "quota", "label", "quota", "label", "quota");
    }

    private static void immutableDefaults() {
        var staged = ImmutableDefaults.newBuilder();
        events("label", "scale");
        var value = staged.build();
        check(Objects.equals(value.getLabel(), "prepared")
                && Double.doubleToRawLongBits(value.getScale()) == Long.MIN_VALUE, "Immutable builder keeps the boxed signed zero");
        events();
        var overridden = ImmutableDefaults.newBuilder().withLabel(null).withScale(+0.0).build();
        check(overridden.getLabel() == null && Double.doubleToRawLongBits(overridden.getScale()) == 0L,
                "Immutable explicit null and positive zero");
        events("label", "scale");
        var scale = field(overridden, "scale");
        check(scale.isSet() && Double.doubleToRawLongBits((Double) scale.getDefault()) == Long.MIN_VALUE,
                "Reflected immutable signed-zero default");
        events("scale", "scale");
        reject(scale::unset);
    }

    private static void hookTiming() {
        var direct = HookedDefaults.newInstance();
        check(Objects.equals(direct.getLabel(), "prepared") && direct.getPriority() == 41,
                "Declared default follows and overrides the hook");
        events("hook:null/0", "label", "priority");
        var staged = HookedDefaults.newBuilder();
        events("label", "priority");
        var override = staged.withPriority(73).build();
        check(Objects.equals(override.getLabel(), "prepared") && override.getPriority() == 73,
                "Builder edits win after the hook and defaults");
        events("hook:null/0", "label", "priority");
    }

    private static void documentValues() throws Exception {
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));
        var changed = MutableDefaults.newBuilder().withLabel(null).withQuota(73).build();
        var wire = mapper.writeValueAsString(changed);
        var document = mapper.readTree(wire);
        check(document.has("label") && document.get("label").isNull() && document.get("quota").intValue() == 73,
                "Snapshot preserves explicit null and current scalar values");
        var restored = mapper.readValue(wire, MutableDefaults.class);
        check(restored.getLabel() == null && restored.getQuota() == 73 && field(restored, "quota").isSet(),
                "Construction regains the default provider before loading the snapshot's explicit values");
        ExecutableDefaults.takeEvents();
    }

    private static void inheritedProviders() throws Exception {
        ExecutableDefaults.restartSequence();
        var joined = JoinedDefaults.newInstance();
        check(joined.getAlias().equals("alias-1") && joined.getLabel().equals("base-2"), "effective constructor order");
        events("joined-hook:null/null", "alias-1", "base-2");
        var builder = JoinedDefaults.newBuilder();
        events("alias-3", "base-4");
        joined = builder.build();
        check(joined.getAlias().equals("alias-3") && joined.getLabel().equals("base-4"), "keep staged defaults");
        events("joined-hook:null/null", "alias-5", "base-6");
        AncestorDefaults replacement = ReplacedDefaults.newInstance();
        check(replacement.getLabel().equals("replacement-7"), "one effective provider in shared storage");
        check(field(replacement, "label").getDefault().equals("replacement-8"), "reflection uses the replacement");
        field(replacement, "label").unset();
        check(replacement.getLabel().equals("replacement-9"), "reset uses the replacement");
        events("replacement-7", "replacement-8", "replacement-9");
        check(LiteralChildDefaults.newInstance().getLabel().equals("literal"), "literal supersedes parent provider");
        check(ClearedChildDefaults.newInstance().getLabel() == null, "omission supersedes parent provider");
        events();

        var inherited = InheritedValueDefaults.newBuilder();
        events("base-value-10");
        check(inherited.build().getLabel().equals("base-value-10"), "immutable inherited staged provider");
        events();
        var replaced = ReplacedValueDefaults.newBuilder();
        events("derived-value-11");
        check(replaced.build().getLabel().equals("derived-value-11"), "immutable replacement staged provider");
        check(replaced.withLabel(null).build().getLabel() == null, "explicit null keeps parent provider dormant");
        check(ClearedValueDefaults.newBuilder().build().getLabel() == null, "implicit null keeps parent provider dormant");
        events();
        var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));
        check(mapper.readValue("{}", ReplacedValueDefaults.class).getLabel().equals("derived-value-12"),
                "missing field receives effective default");
        events("derived-value-12");
        var hydrated = mapper.readValue("{\"fixed_label\":null}", ReplacedValueDefaults.class);
        check(hydrated.getLabel() == null, "wire alias can explicitly clear inherited slot");
        events("derived-value-13");
        var serialized = mapper.readTree(mapper.writeValueAsString(hydrated));
        check(serialized.has("fixed_label") && serialized.get("fixed_label").isNull(),
                "serialized null continues to override the derived default");
        events("derived-value-14");
    }

    private static Property field(VObject model, String name) {
        return model.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static void events(String... expected) {
        var observed = ExecutableDefaults.takeEvents();
        check(observed.equals(List.of(expected)), "Expected " + List.of(expected) + " but saw " + observed);
    }

    private static void reject(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("Immutable property reset unexpectedly succeeded.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
