package eu.mihosoft.vmf.conformance.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class TraitProviderDefaultsConformance {
    private TraitProviderDefaultsConformance() { }

    public static void verifyConstruction() {
        TraitDefaultProviders.calls = 0;
        var builder = DefaultedTraitValue.newBuilder();
        require(TraitDefaultProviders.calls == 1 && "shared-1".equals(builder.build().getDisplayLabel())
                        && "shared-1".equals(builder.build().getDisplayLabel()) && TraitDefaultProviders.calls == 1,
                "A builder stages one shared provider result and reuses it on repeated builds");
        var ranked = RankedTraitValue.newBuilder().withRank(3).build();
        require(TraitDefaultProviders.calls == 2 && "shared-2".equals(ranked.getDisplayLabel()) && ranked.getRank() == 3,
                "Separate immutable implementations call the shared owner binding");
        require("shared-3".equals(defaultValue(ranked)) && "shared-4".equals(defaultValue(ranked)),
                "Reflection reads the declared default lazily");
        require("local".equals(LiteralTraitValue.newBuilder().build().getDisplayLabel())
                        && defaultValue(ClearedTraitValue.newBuilder().build()) == null && TraitDefaultProviders.calls == 4,
                "Concrete literal and cleared defaults supersede the role provider");
    }

    public static void exchange(Path directory, boolean write, boolean references, String format, ObjectMapper mapper)
            throws IOException {
        String prefix = (write ? "java" : "csharp") + "-trait-provider-" + (references ? "refs" : "plain");
        for (String state : List.of("omitted", "supplied", "cleared")) {
            String expected = state.equals("supplied") ? "explicit" : null;
            Path file = directory.resolve(prefix + "-" + state + "." + format);
            if (write) {
                String contents;
                if (state.equals("omitted")) {
                    contents = format.equals("xml") ? "<" + DefaultedTraitValue.class.getName() + "/>" : "{}";
                } else {
                    TraitDefaultProviders.calls = 0;
                    var value = DefaultedTraitValue.newBuilder().withDisplayLabel(expected).build();
                    contents = mapper.writeValueAsString(value);
                    require(TraitDefaultProviders.calls == (expected == null ? 2 : 1),
                            "Only an actual null field queries the default during serialization");
                }
                Files.writeString(file, contents);
            }
            for (int repeat = 0; repeat < 2; repeat++) {
                TraitDefaultProviders.calls = 0;
                var value = mapper.readValue(file.toFile(), DefaultedTraitValue.class);
                String wanted = state.equals("omitted") ? "shared-1" : expected;
                require(Objects.equals(wanted, value.getDisplayLabel()) && TraitDefaultProviders.calls == 1,
                        "Every import stages one default before applying the wire override");
            }
        }
        if (format.equals("json")) {
            TraitDefaultProviders.calls = 0;
            var local = mapper.readValue("{}", LiteralTraitValue.class);
            var cleared = mapper.readValue("{}", ClearedTraitValue.class);
            require("local".equals(local.getDisplayLabel()) && cleared.getDisplayLabel() == null
                            && TraitDefaultProviders.calls == 0, "Effective redeclarations suppress the provider on import");
            TraitDefaultProviders.returnsNull = true;
            try {
                var value = DefaultedTraitValue.newBuilder().build();
                String contents = mapper.writeValueAsString(value);
                require(TraitDefaultProviders.calls == 2 && !mapper.readTree(contents).has("label"),
                        "A null-valued provider allows null omission without a new wire field");
            } finally {
                TraitDefaultProviders.returnsNull = false;
            }
        }
    }

    private static Object defaultValue(VObject value) {
        return value.vmf().reflect().propertyByName("displayLabel").orElseThrow().getDefault();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + " (provider calls = " + TraitDefaultProviders.calls + ")");
    }
}
