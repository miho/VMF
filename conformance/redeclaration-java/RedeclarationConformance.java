package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.redeclarations.ConstantExpressionConformance;
import eu.mihosoft.vmf.conformance.redeclarations.ExplicitNull;
import eu.mihosoft.vmf.conformance.redeclarations.FrozenSetting;
import eu.mihosoft.vmf.conformance.redeclarations.LegacyObjectConformance;
import eu.mihosoft.vmf.conformance.redeclarations.MixedSettings;
import eu.mihosoft.vmf.conformance.redeclarations.Reconfigured;
import eu.mihosoft.vmf.conformance.redeclarations.RevisedSetting;
import eu.mihosoft.vmf.conformance.redeclarations.SettingCatalog;
import eu.mihosoft.vmf.conformance.redeclarations.Settings;
import eu.mihosoft.vmf.conformance.redeclarations.Unconfigured;
import eu.mihosoft.vmf.conformance.redeclarations.ValueChoiceConformance;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.runtime.core.Property;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

final class RedeclarationConformance {
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));

    private RedeclarationConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        Path exchange = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(exchange);
            verifyMutableTraits();
            verifyImmutableDefaults();
            Path file = exchange.resolve("java-redeclarations.json");
            MAPPER.writeValue(file.toFile(), specimen());
            verifyWire(file);
            System.out.println("Java verified mixed getter-only traits and scalar redeclarations in typed snapshots.");
        } else {
            verifyWire(exchange.resolve("csharp-redeclarations.json"));
            System.out.println("Java restored the C# scalar redeclarations with their effective aliases and defaults.");
        }
        ConstantExpressionConformance.verify(args[0].equals("write"), exchange);
        LegacyObjectConformance.verify(args[0].equals("write"), exchange);
        ValueChoiceConformance.verify(args[0].equals("write"), exchange);
        StockJsonExchange.verify(exchange, args[0].equals("write"));
    }

    private static void verifyMutableTraits() {
        var concrete = Settings.newInstance();
        MixedSettings trait = concrete;
        check(!hasMethod(MixedSettings.class, "setName") && hasMethod(MixedSettings.class, "setLevel")
                && hasMethod(Settings.class, "setName"), "Only the getter-only trait member omits the setter");
        trait.setLevel(3);
        trait.getMeasures().add(7);
        check(concrete.getLevel() == 3 && concrete.getMeasures().equals(List.of(7)), "The mixed contract exposes its ordinary setter and live list");
        var view = concrete.asReadOnly();
        var changes = new ArrayList<Object>();
        var observer = concrete.vmf().changes().addPropertyListener("name", change ->
                changes.add(change.propertyChange().orElseThrow().newValue()));
        try {
            concrete.setName("assigned");
            var name = property(concrete, "name");
            name.set("reflected");
            check(name.isSet() && "reflected".equals(trait.getName()) && "reflected".equals(view.getName()),
                    "Concrete and reflective writes expose the same inherited storage");
            name.unset();
            check(!name.isSet() && "origin".equals(view.getName()) && "origin".equals(name.getDefault())
                    && changes.equals(List.of("assigned", "reflected", "origin")), "Reset uses the declaration default and emits one ordinary change");
        } finally {
            observer.unsubscribe();
        }

        var derived = Reconfigured.newBuilder().withName("from builder").withLevel(6).build();
        Settings base = derived;
        base.setName("via base");
        base.setLevel(8);
        var name = property(derived, "name");
        var level = property(derived, "level");
        check("via base".equals(derived.getName()) && derived.getLevel() == 8 && name.isSet() && level.isSet(),
                "Base and redeclared setters see the same selected slot");
        name.unset();
        level.unset();
        check(!name.isSet() && !level.isSet() && "reconfigured".equals(base.getName()) && base.getLevel() == 4,
                "Derived reflection resets the inherited storage to its effective defaults");
        base.setLevel(1);
        check(derived.vmf().content().validate().getIssues().size() == 1, "Derived range applies beside the wider inherited constraint");
        base.setLevel(0);
        var issues = derived.vmf().content().validate().getIssues();
        check(issues.size() == 2 && issues.stream().allMatch(issue -> issue.getCode().equals("vmf.range")
                && issue.getPath().equals("/level")), "Both source and inherited ranges still validate the shared property");
    }

    private static SettingCatalog specimen() {
        var original = Settings.newBuilder().withMeasures(7).build();
        var derived = Reconfigured.newBuilder().withMeasures(5, 6).build();
        var cleared = Reconfigured.newBuilder().withName(null).build();
        var implicit = Unconfigured.newInstance();
        var explicit = ExplicitNull.newBuilder().withName("visible").build();
        var frozen = RevisedSetting.newBuilder().withLabel("retained").build();
        return SettingCatalog.newBuilder().withEntries(original, derived, cleared, implicit, explicit)
                .withFrozen(frozen).build();
    }

    private static void verifyWire(Path file) throws IOException {
        var wire = MAPPER.readTree(file.toFile());
        var entriesWire = wire.path("entries");
        check(entriesWire.size() == 5 && "origin".equals(entriesWire.path(0).path("display_name").asText())
                && !entriesWire.path(0).has("name") && "reconfigured".equals(entriesWire.path(1).path("name").asText())
                && !entriesWire.path(1).has("display_name") && entriesWire.path(2).path("name").isNull()
                && !entriesWire.path(3).has("name") && "visible".equals(entriesWire.path(4).path("optional_name").asText()),
                "Inherited alias, dropped alias, explicit null and new alias use their effective wire forms");
        var restored = MAPPER.readValue(file.toFile(), SettingCatalog.class);
        var entries = restored.getEntries();
        check(entries.get(1) instanceof Reconfigured && entries.get(2) instanceof Reconfigured
                && entries.get(3) instanceof Unconfigured && entries.get(4) instanceof ExplicitNull,
                "Typed snapshots restore exact concrete declarations");
        verifyEntry(entries.get(0), "origin", "origin", 2, 2, 1);
        verifyEntry(entries.get(1), "reconfigured", "reconfigured", 4, 4, 2);
        verifyEntry(entries.get(2), null, "reconfigured", 4, 4, 2);
        verifyEntry(entries.get(3), null, null, 0, 0, 1);
        verifyEntry(entries.get(4), "visible", null, 0, 0, 1);
        check(entries.get(0).getMeasures().equals(List.of(7)) && entries.get(1).getMeasures().equals(List.of(5, 6)),
                "Mixed trait collection values round-trip in order");
        check("retained".equals(wire.path("frozen").path("caption").asText()) && !wire.path("frozen").has("label")
                && restored.getFrozen() instanceof RevisedSetting && "retained".equals(restored.getFrozen().getLabel())
                && restored.getFrozen().getLimit() == 6, "Inherited immutable storage uses the effective constructor and wire fields");
    }

    private static void verifyImmutableDefaults() {
        FrozenSetting original = FrozenSetting.newBuilder().build();
        FrozenSetting revised = RevisedSetting.newBuilder().build();
        check("original".equals(original.getLabel()) && original.getLimit() == 0 && revised.getLabel() == null
                && revised.getLimit() == 6, "An immutable redeclaration uses effective defaults through its base contract");
        check(revised.vmf().reflect().properties().stream().filter(item -> item.getName().equals("label")).count() == 1
                && revised.vmf().reflect().propertyByName("label").orElseThrow().getDefault() == null,
                "An immutable redeclaration exposes one effective reflected property");
    }

    private static void verifyEntry(Settings entry, String expectedName, String nameDefault,
                                    int expectedLevel, int levelDefault, int ranges) {
        var name = property(entry, "name");
        var level = property(entry, "level");
        check(Objects.equals(entry.getName(), expectedName) && Objects.equals(name.getDefault(), nameDefault)
                && name.isSet() == !Objects.equals(expectedName, nameDefault) && entry.getLevel() == expectedLevel
                && level.getDefault().equals(levelDefault) && level.getConstraints().size() == ranges,
                "Restored scalars and declaration metadata agree");
        check(entry.vmf().reflect().properties().stream().filter(item -> item.getName().equals("name")).count() == 1,
                "A scalar redeclaration has one reflected slot");
    }

    private static Property property(Settings owner, String name) {
        return owner.vmf().reflect().propertyByName(name).orElseThrow();
    }

    private static boolean hasMethod(Class<?> type, String name) {
        return Arrays.stream(type.getMethods()).anyMatch(method -> method.getName().equals(name));
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
