package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.values.DefaultEntry;
import eu.mihosoft.vmf.conformance.values.DefaultSettings;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class DefaultConformance {
    private static final ObjectMapper PLAIN = mapper(false);
    private static final ObjectMapper REFERENCES = mapper(true);

    private DefaultConformance() {}

    public static void main(String[] args) throws IOException {
        var interchange = Path.of(args[1]);
        if (args[0].equals("read")) {
            verifySnapshot(PLAIN, Files.readString(interchange.resolve("csharp-plain.json")));
            verifySnapshot(REFERENCES, Files.readString(interchange.resolve("csharp-references.json")));
            System.out.println("Java restored explicit nulls from C# snapshots with scalar defaults.");
            return;
        }

        verifyDefaults(DefaultSettings.newInstance());
        verifyDefaults(DefaultSettings.newBuilder().build());
        verifyDefaults(PLAIN.readValue("{}", DefaultSettings.class));
        verifyDefaults(REFERENCES.readValue("{}", DefaultSettings.class));

        var working = verifyBuilder();
        Files.createDirectories(interchange);
        String plain = PLAIN.writeValueAsString(working);
        String references = REFERENCES.writeValueAsString(working);
        verifySnapshot(PLAIN, plain);
        verifySnapshot(REFERENCES, references);
        Files.writeString(interchange.resolve("java-plain.json"), plain);
        Files.writeString(interchange.resolve("java-references.json"), references);
        System.out.println("Java verified scalar defaults, builder edits, and null snapshot overrides.");
    }

    private static DefaultSettings verifyBuilder() {
        var stages = new ArrayList<>(List.of("check", "publish"));
        var builder = DefaultSettings.newBuilder().withName(null).withLimit(null).withStages(stages);
        stages.set(1, "external edit");
        reject(() -> builder.withStages("check", "check"), "duplicate ordered set input");
        reject(() -> builder.withStages("a", "b", "c", "d"), "overfull collection input");

        var first = builder.build();
        first.getStages().move(1, 0);
        var second = builder.build();
        check(first != second && first.getStages().equals(List.of("publish", "check"))
                && second.getStages().equals(List.of("check", "publish")), "Builder keeps its own snapshot");
        var entry = DefaultEntry.newBuilder().build();
        check("new".equals(entry.getLabel()), "Contained child gets its default");
        second.getEntries().add(entry);
        return second;
    }

    private static void verifyDefaults(DefaultSettings model) {
        check("idle\nready".equals(model.getName()) && model.getLimit() == 7, "Inherited nullable scalar defaults");
        check(Double.doubleToRawLongBits(model.getBaseline()) == Long.MIN_VALUE
                && model.getRatio() == 0.3125f && model.getMode() == '中', "Exact floating and UTF-16 defaults");
        check(model.getSequence() == 9_007_199_254_740_993L && Boolean.TRUE.equals(model.getFlag())
                && model.getUnsetValue() == null && model.getNote() == null, "Exact widths and absent defaults");
    }

    private static void verifySnapshot(ObjectMapper mapper, String document) throws IOException {
        var json = mapper.readTree(document);
        check(json.has("display_name") && json.get("display_name").isNull() && json.has("limit") && json.get("limit").isNull()
                && !json.has("note"), "Wire keeps explicit null only when it overrides a non-null scalar default");
        var model = mapper.readValue(document, DefaultSettings.class);
        check(model.getName() == null && model.getLimit() == null, "Explicit null suppresses the assigned default");
        check(model.getStages().equals(List.of("check", "publish")) && model.getEntries().size() == 1
                && "new".equals(model.getEntries().get(0).getLabel()), "Restored builder collection data");
        check(Double.doubleToRawLongBits(model.getBaseline()) == Long.MIN_VALUE, "Snapshot preserves negative zero default");
    }

    private static ObjectMapper mapper(boolean references) {
        return new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static void reject(Runnable edit, String description) {
        try {
            edit.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new IllegalStateException("Accepted " + description);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException(description);
    }
}
