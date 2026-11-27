package eu.mihosoft.vmf.conformance.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class HookedManagedDefaultsConformance {
    private static final List<String> STAGED_PHASES = List.of(
            "numbers:1", "token:1", "values:1", "hook:0", "numbers:2", "token:2", "values:2");
    private static final String MAP_INITIALIZATION = "seed-a>|seed-b>|>saved-2|saved-2>";

    private HookedManagedDefaultsConformance() { }

    public static void exchange(Path directory, boolean write, boolean references, String format, ObjectMapper mapper)
            throws IOException {
        String author = write ? "java" : "csharp";
        String profile = references ? "refs" : "plain";
        for (String state : List.of("omitted", "explicit", "cleared")) {
            var document = directory.resolve(author + "-executable-defaults-" + profile + "-" + state + "." + format);
            if (write) {
                String contents = state.equals("omitted")
                        ? format.equals("xml") ? "<" + HookedManagedDefaults.class.getName() + "/>" : "{}"
                        : mapper.writeValueAsString(specimen(state, references));
                Files.writeString(document, contents);
            }
            // The same provider sequence applies to the first read and later reads.
            HookedManagedDefaultsBehavior.reset();
            verify(mapper.readValue(document.toFile(), HookedManagedDefaults.class), state, references);
        }
    }

    private static HookedManagedDefaults specimen(String state, boolean references) {
        HookedManagedDefaultsBehavior.reset();
        boolean cleared = state.equals("cleared");
        var value = HookedManagedDefaults.newBuilder().withNumbers(cleared ? List.of() : List.of(27))
                .withToken(cleared ? 0 : 57).withValues(cleared ? Map.of() : Map.of("wire", 31)).build();
        if (references) value.setLink(value);
        return value;
    }

    private static void verify(HookedManagedDefaults value, String state, boolean references) {
        check(HookedManagedDefaultsBehavior.phases.equals(STAGED_PHASES),
                "Snapshot stages one builder before its real model constructor");
        check(HookedManagedDefaultsBehavior.lastOwner == value, "The hook receives the restored owner");
        if (references && !state.equals("omitted")) {
            check(value.getLink() == value, "A persisted ID can refer back to the freshly constructed owner");
        } else {
            check(value.getLink() == null, "An omitted reference stays unset");
        }
        switch (state) {
            case "omitted":
                check(value.getNumbers().equals(List.of(1)) && value.getToken() == 100
                                && value.getValues().equals(Map.of("saved-1", 1))
                                && value.observedMapEdits().equals(MAP_INITIALIZATION + "|>saved-1"),
                        "Omitted wire fields use the staged fallback and publish ordered map edits");
                break;
            case "explicit":
                check(value.getNumbers().equals(List.of(27)) && value.getToken() == 57
                                && value.getValues().equals(Map.of("wire", 31))
                                && value.observedMapEdits().equals(MAP_INITIALIZATION + "|>wire"),
                        "Explicit wire fields replace constructor defaults");
                break;
            case "cleared":
                check(value.getNumbers().isEmpty() && value.getToken() == 0 && value.getValues().isEmpty()
                                && value.observedMapEdits().equals(MAP_INITIALIZATION),
                        "Explicit empty wire fields clear constructor defaults");
                break;
            default: throw new AssertionError(state);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message + ": " + HookedManagedDefaultsBehavior.phases);
        }
    }
}
