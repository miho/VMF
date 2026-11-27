package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.ArchiveNode;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.conformance.values.ReadOnlyDefaultSettings;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

final class DynamicDefaultsConformance {
    private DynamicDefaultsConformance() { }

    public static void main(String[] args) throws Exception {
        checkReadOnlyScalarOverride("name", "idle\nready", "custom", "replacement", null,
                ReadOnlyDefaultSettings::getName);
        checkReadOnlyScalarOverride("baseline", -0.0d, 1.5d, 2.5d, -1.0d,
                ReadOnlyDefaultSettings::getBaseline);
        var model = DefaultSettings.newInstance();
        var name = field(model, "name");
        var updates = new ArrayList<String>();
        var subscription = model.vmf().changes().addPropertyListener("name", change -> {
            var edit = change.propertyChange().orElseThrow();
            updates.add(edit.newValue() + "/" + name.getDefault());
        });
        model.vmf().changes().start();
        name.setDefault("custom");
        check("custom".equals(model.getName()) && !name.isSet(), "An unset value follows the default through a live setter");
        model.setName("explicit");
        name.setDefault("explicit");
        check(!name.isSet() && updates.equals(List.of("custom/custom", "explicit/custom")),
                "A set value is preserved and can become unset without an event");
        name.setDefault(null);
        check("idle\nready".equals(model.getName()) && !name.isSet() && model.vmf().changes().all().size() == 3,
                "Clearing an override restores the declared fallback");
        model.vmf().changes().stop();
        subscription.unsubscribe();
        reject(() -> field(model.asReadOnly(), "name").setDefault("hidden"), "Read-only override");

        var profile = CollectionProfile.newInstance();
        var counts = field(profile, "counts");
        var proposed = new ArrayList<>(List.of(2, 1));
        counts.setDefault(proposed);
        proposed.clear();
        check(profile.getCounts().equals(List.of(2, 1)) && !counts.isSet(), "Managed list default is defensive");
        reject(() -> counts.setDefault(List.of(1, 2, 3, 4)), "Bounded list default");
        check(counts.getDefault().equals(List.of(2, 1)) && profile.getCounts().equals(List.of(2, 1)),
                "A failed collection admission preserves current and default values");
        profile.getCounts().set(0, 3);
        counts.unset();
        check(profile.getCounts().equals(List.of(2, 1)), "Collection reset uses the configured default");

        var limits = field(profile, "limits");
        Map<String, Long> source = new LinkedHashMap<>();
        source.put("pending", null);
        source.put("value", 2L);
        limits.setDefault(source);
        source.put("pending", 9L);
        check(profile.getLimits().containsKey("pending") && profile.getLimits().get("pending") == null,
                "Managed map keeps a present null");
        profile.getLimits().remove("pending");
        profile.getLimits().put("pending", null);
        check(!limits.isSet(), "Map membership equality ignores insertion order");
        limits.unset();
        check(new ArrayList<>(profile.getLimits().keySet()).equals(List.of("pending", "value")),
                "Reset reinstates default insertion order");

        var node = ArchiveNode.newInstance();
        var target = ArchiveNode.newBuilder().withName("same").build();
        var other = ArchiveNode.newBuilder().withName("same").build();
        var peers = field(node, "peers");
        peers.setDefault(List.of(target, other));
        check(!peers.isSet() && node.getPeers().size() == 2, "Reference sets keep identities");
        reject(() -> peers.setDefault(List.of(target, target)), "Duplicate reference target");
        reject(() -> field(node, "children").setDefault(List.of(target)), "Owning relationship default");
        var view = field(node.asReadOnly(), "peers");
        check(((List<?>) view.getDefault()).get(0) == target.asReadOnly(), "Read-only reference defaults project their members");

        var mapper = new ObjectMapper().registerModule(VMFJacksonModule
                .newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(false));
        var restored = mapper.readValue(mapper.writeValueAsString(profile), CollectionProfile.class);
        check(restored.getCounts().equals(List.of(2, 1)) && field(restored, "counts").getDefault().equals(List.of()),
                "Documents store current values without their default override");
        System.out.println("Java verified per-instance scalar, collection and reference defaults.");
    }

    private static void checkReadOnlyScalarOverride(String name, Object declared, Object configured,
                                                   Object replacement, Object explicit,
                                                   Function<ReadOnlyDefaultSettings, Object> getter) {
        var model = DefaultSettings.newInstance();
        var source = field(model, name);
        var view = model.asReadOnly();
        var projected = field(view, name);
        checkScalarState(source, projected, getter.apply(view), declared, declared, false);

        source.setDefault(configured);
        checkScalarState(source, projected, getter.apply(view), configured, configured, false);
        source.set(explicit);
        checkScalarState(source, projected, getter.apply(view), configured, explicit, true);
        source.setDefault(replacement);
        checkScalarState(source, projected, getter.apply(view), replacement, explicit, true);

        reject(() -> projected.setDefault(configured), "Read-only default override");
        reject(projected::unset, "Read-only reset");
        checkScalarState(source, projected, getter.apply(view), replacement, explicit, true);
        source.unset();
        checkScalarState(source, projected, getter.apply(view), replacement, replacement, false);

        check(declared.equals(field(DefaultSettings.newInstance(), name).getDefault()), "Defaults are instance-local");
        source.setDefault(null);
        checkScalarState(source, projected, getter.apply(view), declared, declared, false);
    }

    private static void checkScalarState(Property source, Property view, Object nativeValue,
                                         Object expectedDefault, Object value, boolean set) {
        check(expectedDefault.equals(source.getDefault()) && expectedDefault.equals(view.getDefault()),
                "Default should be " + expectedDefault + " on both views of " + source.getName());
        check(Objects.equals(value, source.get()) && Objects.equals(value, view.get()) && Objects.equals(value, nativeValue)
                && source.isSet() == set && view.isSet() == set,
                "Current value and set state should agree on both views of " + source.getName());
    }

    private static Property field(VObject model, String name) {
        return model.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static void reject(Runnable attempt, String operation) {
        try {
            attempt.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError(operation + " unexpectedly succeeded");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
