package eu.mihosoft.vmf.conformance.behavior;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

final class RedeclaredManagedDefaultsConformance {
    private static final class Specimen {
        final String name;
        final Class<? extends InheritedManagedReferences> type;
        final Supplier<? extends InheritedManagedReferences> create;
        final Supplier<? extends InheritedManagedReferences> build;

        Specimen(String name, Class<? extends InheritedManagedReferences> type,
                 Supplier<? extends InheritedManagedReferences> create,
                 Supplier<? extends InheritedManagedReferences> build) {
            this.name = name;
            this.type = type;
            this.create = create;
            this.build = build;
        }

        boolean boundNumbers() { return name.equals("replacement") || name.equals("mixed"); }
        boolean boundSettings() { return name.equals("replacement"); }
        boolean emptyLiteral() { return name.equals("empty"); }
    }

    private static final List<Specimen> SPECIMENS = List.of(
            new Specimen("replacement", RedeclaredManagedReplacement.class, RedeclaredManagedReplacement::newInstance,
                    () -> RedeclaredManagedReplacement.newBuilder().build()),
            new Specimen("empty", RedeclaredManagedEmpty.class, RedeclaredManagedEmpty::newInstance,
                    () -> RedeclaredManagedEmpty.newBuilder().build()),
            new Specimen("none", RedeclaredManagedNone.class, RedeclaredManagedNone::newInstance,
                    () -> RedeclaredManagedNone.newBuilder().build()),
            new Specimen("mixed", RedeclaredManagedMixed.class, RedeclaredManagedMixed::newInstance,
                    () -> RedeclaredManagedMixed.newBuilder().build()));

    private RedeclaredManagedDefaultsConformance() { }

    static void verifyConstruction() {
        for (Specimen specimen : SPECIMENS) {
            InheritedManagedDefaultsBehavior.reset();
            check(specimen, specimen.create.get(), false, false);
            InheritedManagedDefaultsBehavior.reset();
            check(specimen, specimen.build.get(), true, false);
        }
        InheritedManagedDefaultsBehavior.reset();
        var parent = InheritedManagedReferences.newInstance();
        InheritedManagedDefaultsBehavior.reset();
        var imported = RedeclaredManagedReplacement.newBuilder().appendCollections(false).applyFrom(parent).build();
        require(imported.getNumbers().equals(List.of(1)) && imported.getSettings().equals(Map.of("saved-1", 1)),
                "The builder imports compatible base storage despite redeclaration");
        require(InheritedManagedDefaultsBehavior.phases.equals(
                        List.of("alternative-numbers:1", "alternative-settings:1", "hook:references",
                                "alternative-numbers:2", "alternative-settings:2")),
                "Importing a base does not invoke the old default providers");
    }

    static void exchange(Path directory, boolean write, boolean references, String format, ObjectMapper mapper)
            throws IOException {
        String author = write ? "java" : "csharp";
        String profile = references ? "refs" : "plain";
        for (Specimen specimen : SPECIMENS) {
            String prefix = author + "-managed-redeclared-" + specimen.name + "-" + profile;
            Path empty = directory.resolve(prefix + "-omitted." + format);
            Path explicit = directory.resolve(prefix + "-supplied." + format);
            if (write) {
                Files.writeString(empty, format.equals("xml") ? "<" + specimen.type.getName() + "/>" : "{}");
                var child = InheritedManagedChild.newBuilder().withLabel("shared").build();
                InheritedManagedDefaultsBehavior.reset();
                var populated = specimen.create.get();
                populated.getNumbers().clear();
                populated.getNumbers().add(27);
                populated.getSettings().clear();
                populated.getSettings().put("wire", 31);
                populated.setChild(child);
                if (references) populated.setSelection(child);
                Files.writeString(explicit, mapper.writeValueAsString(populated));
            }
            for (int attempt = 0; attempt < 2; ++attempt) {
                InheritedManagedDefaultsBehavior.reset();
                check(specimen, mapper.readValue(empty.toFile(), specimen.type), true, false);
                InheritedManagedDefaultsBehavior.reset();
                var populated = mapper.readValue(explicit.toFile(), specimen.type);
                check(specimen, populated, true, true);
                require(populated.getChild().getLabel().equals("shared")
                                && populated.getSelection() == (references ? populated.getChild() : null),
                        "Inherited contained-reference aliases survive the redeclared fields");
            }
        }
    }

    private static void check(Specimen specimen, InheritedManagedReferences value, boolean staged, boolean supplied) {
        List<String> phases = new ArrayList<>();
        if (staged) expectedFactories(specimen, phases, 1);
        phases.add("hook:references");
        expectedFactories(specimen, phases, staged ? 2 : 1);
        require(phases.equals(InheritedManagedDefaultsBehavior.phases), "Only selected factories evaluate in order");

        List<Integer> numbers = supplied ? List.of(27) : specimen.boundNumbers() ? List.of(11)
                : staged || specimen.emptyLiteral() ? List.of() : List.of(99);
        Map<String, Integer> settings = supplied ? Map.of("wire", 31) : specimen.boundSettings()
                ? Map.of("altered-1", 11) : staged || specimen.emptyLiteral() ? Map.of() : Map.of("seed-a", 1, "seed-b", 2);
        require(numbers.equals(value.getNumbers()) && settings.equals(value.getSettings()), "Effective staged contents");
        require(value == InheritedManagedDefaultsBehavior.lastOwner && value.getNumbers() == InheritedManagedDefaultsBehavior.lastNumbers
                        && value.getSettings() == InheritedManagedDefaultsBehavior.lastSettings,
                "The hook's typed listeners remain attached to the same live slots");

        List<String> listEvents = new ArrayList<>();
        if (specimen.boundNumbers()) {
            listEvents.add(staged ? "[99]>[12]" : "[99]>[11]");
            if (staged) listEvents.add("[12]>" + numbers);
        } else if (specimen.emptyLiteral()) {
            listEvents.add("[99]>[]");
            if (supplied) listEvents.add("[]>[27]");
        } else if (staged) {
            listEvents.add("[99]>" + numbers);
        }
        List<String> mapEvents = new ArrayList<>();
        if (staged || specimen.boundSettings() || specimen.emptyLiteral()) mapEvents.addAll(List.of("seed-a>", "seed-b>"));
        if (specimen.boundSettings()) {
            if (staged) mapEvents.addAll(List.of(">altered-2", "altered-2>"));
            mapEvents.add(supplied ? ">wire" : ">altered-1");
        } else if (supplied) {
            mapEvents.add(">wire");
        }
        require(listEvents.equals(InheritedManagedDefaultsBehavior.numbersEdits), "List replacement events");
        require(mapEvents.equals(InheritedManagedDefaultsBehavior.settingsEdits), "Map removals followed by additions");
    }

    private static void expectedFactories(Specimen specimen, List<String> phases, int call) {
        if (specimen.boundNumbers()) phases.add("alternative-numbers:" + call);
        if (specimen.boundSettings()) phases.add("alternative-settings:" + call);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message + ": " + InheritedManagedDefaultsBehavior.phases + ", "
                + InheritedManagedDefaultsBehavior.numbersEdits + ", " + InheritedManagedDefaultsBehavior.settingsEdits);
    }
}
