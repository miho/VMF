package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.CollectionProfile;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

final class CollectionConformance {
    private static final ObjectMapper PLAIN = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final ObjectMapper REFERENCES = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));

    private CollectionConformance() {}

    public static void main(String[] args) throws IOException {
        Path interchange = Path.of(args[1]);
        if (args[0].equals("read")) {
            verify(PLAIN.readValue(interchange.resolve("collections-csharp-plain.json").toFile(), CollectionProfile.class));
            verify(REFERENCES.readValue(interchange.resolve("collections-csharp-references.json").toFile(), CollectionProfile.class));
            ScalarListSortConformance.exchange(interchange, false);
            ListPredicateConformance.exchange(interchange, false);
            ListReplacementConformance.exchange(interchange, false);
            CollectionApplyConformance.exchange(interchange, false);
            System.out.println("Java restored the C# managed scalar collections in both JSON profiles.");
            return;
        }

        var fixture = Path.of(args[2]).toFile();
        var model = PLAIN.readValue(fixture, CollectionProfile.class);
        verify(model);
        verifyEdits(PLAIN.readValue(fixture, CollectionProfile.class));
        String json = Files.readString(fixture.toPath());
        rejectSnapshot(json.replace("\"phases\": [\"calibrate\", \"measure\"]", "\"phases\": [\"calibrate\", \"calibrate\"]"));
        rejectSnapshot(json.replace("\"levels\": [0.0, -0.0, \"NaN\"]", "\"levels\": [0.0, 0.0]"));
        rejectSnapshot(json.replace("{\"key\": -0.0, \"value\": \"-\"}", "{\"key\": 0.0, \"value\": \"-\"}"));
        rejectSnapshot(json.replace("{\"key\": -0.0, \"value\": \"-\"}", "{\"key\": null, \"value\": \"-\"}"));
        Files.createDirectories(interchange);
        PLAIN.writeValue(interchange.resolve("collections-java-plain.json").toFile(), model);
        REFERENCES.writeValue(interchange.resolve("collections-java-references.json").toFile(), model);
        ScalarListSortConformance.verifyNative();
        ScalarListSortConformance.exchange(interchange, true);
        ListPredicateConformance.verifyNative();
        ListPredicateConformance.exchange(interchange, true);
        ListReplacementConformance.verifyNative();
        ListReplacementConformance.exchange(interchange, true);
        CollectionApplyConformance.exchange(interchange, true);
        System.out.println("Java verified managed scalar collections and maps.");
    }

    private static void verify(CollectionProfile model) {
        check(model.getMessages().equals(Arrays.asList("ready", null, "ready")), "Sequence preserves nulls and repeats");
        check(model.getPhases().equals(List.of("calibrate", "measure")), "Ordered unique scalar values");
        check(model.getCounts().equals(List.of(1, 2)), "Checked scalar list");
        check(model.getReadings().indexOf(+0.0) == 0 && !model.getReadings().contains(-0.0)
                && !model.asReadOnly().getReadings().contains(-0.0), "Scalar list equality distinguishes signed zeros");
        check(Boolean.TRUE.equals(model.getOptions().get("@vmf-id"))
                && Boolean.FALSE.equals(model.getOptions().get("@vmf-type")), "String map keys are ordinary data");
        check(model.getLevels().size() == 3 && model.getLevels().contains(+0.0)
                && model.getLevels().contains(-0.0) && model.getLevels().contains(Double.NaN),
                "Set distinguishes signed zeros and coalesces NaNs");
        check(model.getLimits().get("limit") == Long.MAX_VALUE && model.getLimits().containsKey("optional")
                && model.getLimits().get("optional") == null && !model.getLimits().containsKey("missing"),
                "String map distinguishes present null and missing");
        check(model.getMarkers().size() == 3 && model.getMarkers().get(+0.0) == '+'
                && model.getMarkers().get(-0.0) == '-' && model.getMarkers().containsKey(Double.NaN)
                && model.getMarkers().get(Double.NaN) == null, "Typed map retains exact keys");
        check(model.vmf().content().validate().isValid(), "Collection fixture is valid");
    }

    private static void verifyEdits(CollectionProfile model) {
        var view = model.asReadOnly();
        var phases = model.getPhases();
        check(!phases.add("calibrate"), "Duplicate Add is a no-op");
        reject(() -> phases.add(0, "measure"), "duplicate indexed insertion");
        reject(() -> phases.addAll(List.of("review", "publish")), "overfull ordered set");
        check(phases.equals(List.of("calibrate", "measure")), "Ordered bulk failure is atomic");
        phases.addAll(List.of("measure", "review", "review"));
        phases.move(2, 0);
        check(view.getPhases().equals(List.of("review", "calibrate", "measure")), "View follows filtered adds and moves");

        reject(() -> model.getCounts().addAll(List.of(3, 4)), "overfull list");
        check(model.getCounts().equals(List.of(1, 2)), "List bulk failure is atomic");

        var limits = model.getLimits();
        var edits = new LinkedHashMap<String, Long>();
        edits.put("limit", 1L);
        edits.put("new", 3L);
        edits.put("extra", 2L);
        reject(() -> limits.putAll(edits), "overfull map");
        check(limits.get("limit") == Long.MAX_VALUE && limits.size() == 2 && !limits.containsKey("new"),
                "Map bulk failure preserves updates and additions");
        limits.put("new", 3L);
        limits.put("limit", 1L);
        check(view.getLimits().get("limit") == 1L && view.getLimits().size() == 3, "View follows map updates at capacity");

        var levels = model.getLevels();
        check(!levels.add(Double.longBitsToDouble(0x7ff800000000000FL)), "NaN payloads compare equal");
        check(levels.add(2.0), "Set accepts a new member");
        reject(() -> levels.add(3.0), "overfull set");
        check(levels.size() == 4 && !levels.contains(3.0), "Rejected edit preserves set capacity");
        levels.clear();
        limits.clear();
        check(model.vmf().content().validate().getIssues().stream().anyMatch(issue -> "/levels".equals(issue.getPath()))
                && model.vmf().content().validate().getIssues().stream().anyMatch(issue -> "/limits".equals(issue.getPath())),
                "Lower multiplicity is checked by validation");
    }

    private static void rejectSnapshot(String json) throws IOException {
        try {
            PLAIN.readValue(json, CollectionProfile.class);
        } catch (IOException expected) {
            return;
        }
        throw new IllegalStateException("Accepted a malformed managed collection snapshot.");
    }

    private static void reject(Runnable edit, String operation) {
        try {
            edit.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Accepted " + operation);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
