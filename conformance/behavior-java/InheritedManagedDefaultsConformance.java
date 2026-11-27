package eu.mihosoft.vmf.conformance.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class InheritedManagedDefaultsConformance {
    private InheritedManagedDefaultsConformance() { }

    public static void verifyConstruction() {
        InheritedManagedDefaultsBehavior.reset();
        var direct = InheritedManagedValues.newInstance();
        checkState(direct, "values", false, List.of(1), Map.of("saved-1", 1),
                List.of("[99]>[1]"), List.of("seed-a>", "seed-b>", ">saved-1"));

        InheritedManagedDefaultsBehavior.reset();
        var builder = InheritedManagedValues.newBuilder();
        check(InheritedManagedDefaultsBehavior.lastOwner == null
                        && InheritedManagedDefaultsBehavior.phases.equals(List.of("numbers:1", "settings:1")),
                "A builder evaluates exactly its buffered values before constructing a resident graph");
        checkStaged(builder.build(), "values", false, false);
        verifyDerivedConstruction();
        RedeclaredManagedDefaultsConformance.verifyConstruction();
    }

    private static void verifyDerivedConstruction() {
        InheritedManagedDefaultsBehavior.reset();
        var direct = InheritedManagedDerived.newInstance();
        checkState(direct, "references", false, List.of(1), Map.of("saved-1", 1),
                List.of("[99]>[1]"), List.of("seed-a>", "seed-b>", ">saved-1"));

        InheritedManagedDefaultsBehavior.reset();
        var overridden = InheritedManagedOverridden.newInstance();
        checkState(overridden, "replacement", false, List.of(1), Map.of("saved-1", 1),
                List.of("[99]>[1]"), List.of("seed-a>", "seed-b>", ">saved-1"));

        InheritedManagedDefaultsBehavior.reset();
        var derivedBuilder = InheritedManagedDerived.newBuilder();
        check(InheritedManagedDefaultsBehavior.lastOwner == null
                        && InheritedManagedDefaultsBehavior.phases.equals(List.of("numbers:1", "settings:1")),
                "A derived builder stages values before creating its owner");
        checkStaged(derivedBuilder.build(), "references", false, false);

        InheritedManagedDefaultsBehavior.reset();
        var overriddenBuilder = InheritedManagedOverridden.newBuilder().withNumbers(List.of()).withSettings(Map.of());
        checkStaged(overriddenBuilder.build(), "replacement", false, true);
    }

    public static void exchange(Path directory, boolean write, boolean references, String format, ObjectMapper mapper)
            throws IOException {
        String author = write ? "java" : "csharp";
        String profile = references ? "refs" : "plain";
        var omitted = directory.resolve(author + "-inherited-managed-" + profile + "-omitted." + format);
        var explicit = directory.resolve(author + "-inherited-managed-" + profile + "-explicit." + format);
        if (write) {
            Files.writeString(omitted, format.equals("xml") ? "<" + InheritedManagedValues.class.getName() + "/>" : "{}");
            var child = InheritedManagedChild.newBuilder().withLabel("child").build();
            InheritedManagedDefaultsBehavior.reset();
            var value = InheritedManagedReferences.newBuilder().withNumbers(List.of(27)).withSettings(Map.of("wire", 31))
                    .withChild(child).build();
            if (references) value.setSelection(child);
            checkStaged(value, "references", true, false);
            Files.writeString(explicit, mapper.writeValueAsString(value));
        }
        // Reuse one mapper so both its cold and warm reads prove there is no metadata-time construction.
        for (int attempt = 0; attempt < 2; attempt++) {
            InheritedManagedDefaultsBehavior.reset();
            checkStaged(mapper.readValue(omitted.toFile(), InheritedManagedValues.class), "values", false, false);
            InheritedManagedDefaultsBehavior.reset();
            var value = mapper.readValue(explicit.toFile(), InheritedManagedReferences.class);
            checkStaged(value, "references", true, false);
            check("child".equals(value.getChild().getLabel())
                            && (references ? value.getChild() == value.getSelection() : value.getSelection() == null),
                    "Reference-aware snapshots retain the aliased contained child");
        }
        if (format.equals("json")) {
            InheritedManagedDefaultsBehavior.reset();
            checkStaged(mapper.readValue("{\"counts\":[],\"settings\":{}}", InheritedManagedValues.class), "values", false, true);
            if (references) {
                InheritedManagedDefaultsBehavior.reset();
                String forwardAlias = "{\"picked\":\"child-1\",\"child\":{\"@vmf-id\":\"child-1\",\"label\":\"child\"}}";
                var restored = mapper.readValue(forwardAlias, InheritedManagedReferences.class);
                checkStaged(restored, "references", false, false);
                check(restored.getChild() == restored.getSelection(), "The staged fallback preserves a forward reference");
            }
        }
        exchangeDerived(directory, write, references, format, mapper);
        RedeclaredManagedDefaultsConformance.exchange(directory, write, references, format, mapper);
    }

    private static void exchangeDerived(Path directory, boolean write, boolean references, String format, ObjectMapper mapper)
            throws IOException {
        String author = write ? "java" : "csharp";
        String prefix = author + "-inherited-managed-" + (references ? "refs" : "plain");
        var omitted = directory.resolve(prefix + "-derived." + format);
        var explicit = directory.resolve(prefix + "-overridden." + format);
        if (write) {
            Files.writeString(omitted, format.equals("xml") ? "<" + InheritedManagedDerived.class.getName() + "/>" : "{}");
            var child = InheritedManagedChild.newBuilder().withLabel("child").build();
            InheritedManagedDefaultsBehavior.reset();
            var value = InheritedManagedOverridden.newBuilder().withNumbers(List.of(27)).withSettings(Map.of("wire", 31))
                    .withChild(child).build();
            if (references) value.setSelection(child);
            Files.writeString(explicit, mapper.writeValueAsString(value));
            checkStaged(value, "replacement", true, false);
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            InheritedManagedDefaultsBehavior.reset();
            checkStaged(mapper.readValue(omitted.toFile(), InheritedManagedDerived.class), "references", false, false);
            InheritedManagedDefaultsBehavior.reset();
            var value = mapper.readValue(explicit.toFile(), InheritedManagedOverridden.class);
            checkStaged(value, "replacement", true, false);
            check("child".equals(value.getChild().getLabel())
                            && (references ? value.getChild() == value.getSelection() : value.getSelection() == null),
                    "A derived snapshot preserves its inherited child's alias and collection state");
        }
    }

    private static void checkStaged(InheritedManagedFields value, String kind, boolean explicit, boolean cleared) {
        List<Integer> numbers = cleared ? List.of() : explicit ? List.of(27) : List.of(1);
        Map<String, Integer> settings = cleared ? Map.of() : explicit ? Map.of("wire", 31) : Map.of("saved-1", 1);
        String addedKey = explicit ? ">wire" : ">saved-1";
        checkState(value, kind, true, numbers, settings,
                List.of("[99]>[2]", "[2]>" + numbers),
                cleared ? List.of("seed-a>", "seed-b>", ">saved-2", "saved-2>")
                        : List.of("seed-a>", "seed-b>", ">saved-2", "saved-2>", addedKey));
    }

    private static void checkState(InheritedManagedFields value, String kind, boolean staged, List<Integer> numbers,
                                   Map<String, Integer> settings, List<String> numbersEdits, List<String> settingsEdits) {
        var phases = staged ? List.of("numbers:1", "settings:1", "hook:" + kind, "numbers:2", "settings:2")
                : List.of("hook:" + kind, "numbers:1", "settings:1");
        check(value == InheritedManagedDefaultsBehavior.lastOwner && value.getNumbers() == InheritedManagedDefaultsBehavior.lastNumbers
                        && value.getSettings() == InheritedManagedDefaultsBehavior.lastSettings,
                "The hook and later writes share the final owner's collection identities");
        check(value.getNumbers().equals(numbers) && value.getSettings().equals(settings), "Managed fields retain final staged state");
        check(InheritedManagedDefaultsBehavior.phases.equals(phases), "Constructor and builder evaluate once per phase");
        check(InheritedManagedDefaultsBehavior.numbersEdits.equals(numbersEdits), "List events preserve replacement phases");
        check(InheritedManagedDefaultsBehavior.settingsEdits.equals(settingsEdits), "Map events preserve individual removals and additions");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + ": " + InheritedManagedDefaultsBehavior.phases + ", "
                + InheritedManagedDefaultsBehavior.numbersEdits + ", " + InheritedManagedDefaultsBehavior.settingsEdits);
    }
}
