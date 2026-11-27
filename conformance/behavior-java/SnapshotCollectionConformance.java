package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import eu.mihosoft.vmf.conformance.behavior.HookedLiteralCollections;
import eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaultsConformance;
import eu.mihosoft.vmf.conformance.behavior.HookedSnapshotCollections;
import eu.mihosoft.vmf.conformance.behavior.ImmutableExecutableDefaultConformance;
import eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsConformance;
import eu.mihosoft.vmf.conformance.behavior.SnapshotCollectionDefaults;
import eu.mihosoft.vmf.conformance.behavior.TraitProviderDefaultsConformance;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

final class SnapshotCollectionConformance {
    private SnapshotCollectionConformance() { }

    static void exchange(Path directory, boolean write) throws IOException {
        verifyHookedBuilds();
        verifyLiteralDefaults(HookedLiteralCollections.newInstance());
        verifyLiteralDefaults(HookedLiteralCollections.newBuilder().build());
        InheritedManagedDefaultsConformance.verifyConstruction();
        TraitProviderDefaultsConformance.verifyConstruction();
        ImmutableExecutableDefaultConformance.verifyConstruction();
        for (boolean references : new boolean[]{false, true}) {
            for (String format : List.of("json", "yaml", "xml")) {
                ObjectMapper mapper = format.equals("xml") ? new XmlMapper()
                        : format.equals("yaml") ? new ObjectMapper(new YAMLFactory()) : new ObjectMapper();
                mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
                String prefix = (write ? "java-" : "csharp-") + "hooked-" + (references ? "refs-" : "plain-");
                for (String state : List.of("omitted", "empty", "populated")) {
                    var document = directory.resolve(prefix + state + "." + format);
                    if (write) {
                        String contents = state.equals("omitted") ? omission(format, HookedSnapshotCollections.class)
                                : mapper.writeValueAsString(specimen(state));
                        Files.writeString(document, contents);
                    }
                    verify(mapper.readValue(document.toFile(), HookedSnapshotCollections.class), state);
                }
                HookedManagedDefaultsConformance.exchange(directory, write, references, format, mapper);
                InheritedManagedDefaultsConformance.exchange(directory, write, references, format, mapper);
                TraitProviderDefaultsConformance.exchange(directory, write, references, format, mapper);
                ImmutableExecutableDefaultConformance.exchange(directory, write, references, format, mapper);
                var defaults = mapper.readValue(omission(format, SnapshotCollectionDefaults.class), SnapshotCollectionDefaults.class);
                check(defaults.getDefaulted().equals(List.of(7)), "An omitted field retains its declared default");
                verifyLiteralDefaults(mapper.readValue(omission(format, HookedLiteralCollections.class), HookedLiteralCollections.class));
                if (format.equals("json")) {
                    var explicit = mapper.readValue("{\"numbers\":[8],\"values\":{\"present\":null}}", HookedLiteralCollections.class);
                    check(explicit.getNumbers().equals(List.of(8)) && explicit.getValues().size() == 1
                                    && explicit.getValues().containsKey("present") && explicit.getValues().get("present") == null
                                    && explicit.observedMapEdits().equals("seed-a>|seed-b>|>saved|saved>|>present"),
                            "An explicit snapshot field replaces the post-hook defaults");
                }
            }
        }
    }

    private static void verifyHookedBuilds() {
        var direct = HookedSnapshotCollections.newInstance();
        check(direct.getNumbers().equals(List.of(1)) && direct.getOrdered().equals(List.of("seed"))
                        && direct.getFlags().equals(Set.of(1)) && direct.getValues().size() == 2
                        && direct.observedMapEdits().isEmpty(), "Direct construction keeps hook seeds");

        var builder = HookedSnapshotCollections.newBuilder();
        verify(builder.build(), "omitted");
        verify(builder.appendCollections(false).withNumbers(List.of()).withOrdered(List.of()).withFlags(Set.of())
                .withValues(java.util.Map.of()).withLegacy(List.of()).build(), "empty");

        var entries = new java.util.LinkedHashMap<String, Integer>();
        entries.put("present", null);
        entries.put("next", 2);
        builder.withNumbers(List.of(2, 2)).withOrdered(List.of("last", "first")).withFlags(Set.of(2, 3))
                .withValues(entries).withLegacy(List.of("filled"));
        verify(builder.build(), "populated");
        verify(builder.build(), "populated");

        var full = HookedSnapshotCollections.newBuilder().withNumbers(List.of(8, 8, 8))
                .withOrdered(List.of("x", "y", "z")).build();
        check(full.getNumbers().equals(List.of(8, 8, 8)) && full.getOrdered().equals(List.of("x", "y", "z"))
                        && full.getValues().isEmpty() && full.getLegacy().equals(List.of("seed")),
                "Build validates replacement capacity independently of the hook's seeds");

        var target = HookedSnapshotCollections.newInstance();
        var transfer = HookedSnapshotCollections.newBuilder().withNumbers(List.of(2)).withLegacy(List.of("tail"))
                .withValues(entries);
        transfer.applyTo(target);
        check(target.getNumbers().equals(List.of(1, 2)) && target.getLegacy().equals(List.of("seed", "tail"))
                        && ">present,next".equals(target.observedMapEdits()), "ApplyTo still appends by default");
        transfer.appendCollections(false).applyTo(target);
        check(target.getNumbers().equals(List.of(2)) && target.getLegacy().equals(List.of("tail"))
                        && ">present,next|seed-a>|seed-b>".equals(target.observedMapEdits()),
                "Explicit replacement clears earlier values through the same map listeners");
    }

    private static void verifyLiteralDefaults(HookedLiteralCollections value) {
        check(value.getNumbers().equals(List.of(7)) && value.getOrdered().equals(List.of("ready"))
                        && value.getFlags().equals(Set.of(3)) && value.getValues().equals(java.util.Map.of("saved", 7))
                        && value.observedMapEdits().equals("seed-a>|seed-b>|>saved"),
                "Literal defaults replace the hook seeds with builder map callback groups");
    }

    private static HookedSnapshotCollections specimen(String state) {
        var value = HookedSnapshotCollections.newInstance();
        value.getNumbers().clear();
        value.getOrdered().clear();
        value.getFlags().clear();
        value.getValues().clear();
        value.getLegacy().clear();
        if (state.equals("populated")) {
            value.getNumbers().addAll(List.of(2, 2));
            value.getOrdered().addAll(List.of("last", "first"));
            value.getFlags().addAll(Set.of(2, 3));
            value.getValues().put("present", null);
            value.getValues().put("next", 2);
            value.getLegacy().add("filled");
        }
        return value;
    }

    private static String omission(String format, Class<?> model) {
        return format.equals("xml") ? "<" + model.getName() + "/>" : "{}";
    }

    private static void verify(HookedSnapshotCollections restored, String state) {
        boolean populated = state.equals("populated");
        check(restored.getNumbers().equals(populated ? List.of(2, 2) : List.of())
                        && restored.getOrdered().equals(populated ? List.of("last", "first") : List.of())
                        && restored.getFlags().equals(populated ? Set.of(2, 3) : Set.of()), "Managed collections replace hook contents");
        check(restored.getValues().size() == (populated ? 2 : 0),
                "A managed map replaces the hook's entries");
        if (populated) {
            check(List.copyOf(restored.getValues().keySet()).equals(List.of("present", "next"))
                            && restored.getValues().get("present") == null && restored.getValues().get("next") == 2,
                    "Managed map has the restored order and present null");
        }
        check(restored.getLegacy().equals(populated ? List.of("seed", "filled") : List.of("seed")),
                "The legacy unbounded list appends to the hook contents");
        String expectedEdits = populated ? "seed-a>|seed-b>|>present,next" : "seed-a>|seed-b>";
        check(expectedEdits.equals(restored.observedMapEdits()), "Managed map import publishes builder edit groups");
        check(restored.invocation() == 1 && restored.invocation() == 2, "Delegated state starts fresh");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
