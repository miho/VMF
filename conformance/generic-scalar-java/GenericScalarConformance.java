package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.conformance.genericscalars.Counts;
import eu.mihosoft.vmf.conformance.genericscalars.Flags;
import eu.mihosoft.vmf.conformance.genericscalars.Labels;
import eu.mihosoft.vmf.conformance.genericscalars.Longs;
import eu.mihosoft.vmf.conformance.genericscalars.ReadOnlySeries;
import eu.mihosoft.vmf.conformance.genericscalars.Series;
import eu.mihosoft.vmf.conformance.genericscalars.Telemetry;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GenericScalarConformance {
    private static final String CHECKPOINT = "origin";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(
            VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL));
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "conformance.unbounded-series"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return type(object); }
        @Override public String type(VObject object) {
            if (object instanceof Telemetry) return "telemetry";
            if (object instanceof Counts) return "counts";
            if (object instanceof Labels) return "labels";
            throw new IllegalArgumentException("Unknown record owner");
        }
        @Override public VObject create(String type, String id) {
            if (!type.equals(id)) throw new IllegalArgumentException("Unexpected record ID");
            switch (type) {
                case "telemetry": return Telemetry.newInstance();
                case "counts": return Counts.newInstance();
                case "labels": return Labels.newInstance();
                default: throw new IllegalArgumentException("Unknown record type");
            }
        }
        @Override public String propertyKey(VObject object, String name) {
            return name.equals("latest") ? "LATEST" : name;
        }
        @Override public String propertyName(VObject object, String key) {
            return key.equals("LATEST") ? "latest" : key;
        }
    };

    private GenericScalarConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        var directory = Path.of(args[1]);
        if (args[0].equals("write")) {
            Files.createDirectories(directory);
            var working = specimen();
            var checkpoint = directory.resolve("java-checkpoint.json");
            MAPPER.writeValue(checkpoint.toFile(), working);
            verifyTyped(working);
            var journal = JournalRecorder.recordFrame(working, BINDING, CHECKPOINT, 8, current -> {
                current.getCounts().setLatest(13);
                current.getLabels().setLatest(null);
                current.getCounts().getSamples().add(1, 9);
                current.getLabels().getSamples().set(0, "restored");
                current.getCounts().getNamed().put("paused", 13);
                current.getLabels().getNamed().put("new", null);
            });
            Files.writeString(directory.resolve("java-journal.json"), VMFJournalCodec.write(journal));
            verify(directory, "java");
        } else {
            verify(directory, "csharp");
        }
        SecondaryScalarConformance.verify(args[0].equals("write"), directory);
        OpenScalarChannelConformance.verify(args[0].equals("write"), directory);
        ScalarHookConformance.verify(args[0].equals("write"), directory);
        System.out.println("Java verified the typed snapshot and string map journal for unbounded scalar generics.");
    }

    private static Telemetry specimen() {
        var waiting = new LinkedHashMap<String, Integer>();
        waiting.put("paused", null);
        var counts = Counts.newBuilder().withSamples(Arrays.asList(7, null, 7)).withNamed(waiting).build();
        var labels = Labels.newBuilder().withLatest("draft").withSamples(Arrays.asList(null, "start"))
                .withNamed(Map.of("stable", "start")).build();
        return Telemetry.newBuilder().withCounts(counts).withLabels(labels).build();
    }

    private static void verifyTyped(Telemetry model) {
        Series<Integer> counts = model.getCounts();
        Series<String> labels = model.getLabels();
        ReadOnlySeries<Integer> countView = counts.asReadOnly();
        ReadOnlySeries<String> labelView = labels.asReadOnly();
        check(countView.getLatest() == null && labelView.getLatest().equals("draft"), "typed closed references");

        Series<Long> widened = Longs.newInstance();
        check(widened.getLatest() == null, "forwarded numeric wrapper starts null");
        widened.setLatest(Long.MAX_VALUE);
        check(widened.asReadOnly().getLatest() == Long.MAX_VALUE, "forwarded numeric wrapper value");
        Series<Boolean> flags = Flags.newInstance();
        check(flags.getLatest() == null, "forwarded boolean wrapper starts null");
        flags.setLatest(false);
        check(Boolean.FALSE.equals(flags.asReadOnly().getLatest()), "forwarded boolean wrapper value");

        var valueDescriptor = counts.vmf().reflect().propertyByName("latest").orElseThrow().getType().getDescriptor();
        var itemsDescriptor = counts.vmf().reflect().propertyByName("samples").orElseThrow().getType().getDescriptor();
        check(valueDescriptor.getName().equals(Integer.class.getName())
                && itemsDescriptor.getArguments().get(0).getName().equals(Integer.class.getName()),
                "reflection sees exact named boxed closure");
    }

    private static void verify(Path directory, String author) throws IOException {
        var checkpointFile = directory.resolve(author + "-checkpoint.json").toFile();
        check(MAPPER.readTree(checkpointFile).findValue("latest_value") != null, "snapshot wire name");
        var checkpoint = MAPPER.readValue(checkpointFile, Telemetry.class);
        var journal = VMFJournalCodec.read(Files.readString(directory.resolve(author + "-journal.json")));
        check(journal.wireVersion() == 1, "scalar collection journal version");
        var result = JournalReplayer.replay(checkpoint, BINDING, CHECKPOINT, journal);
        check(checkpoint.getCounts().getLatest() == null && checkpoint.getCounts().getSamples().get(1) == null
                && checkpoint.getCounts().getNamed().containsKey("paused")
                && checkpoint.getCounts().getNamed().get("paused") == null
                && Objects.equals(checkpoint.getLabels().getLatest(), "draft")
                && !checkpoint.getLabels().getNamed().containsKey("new"), "checkpoint unchanged");
        check(result.getCounts().getLatest() == 13
                && result.getCounts().getSamples().equals(Arrays.asList(7, 9, null, 7))
                && result.getCounts().getNamed().get("paused") == 13
                && result.getLabels().getLatest() == null
                && result.getLabels().getSamples().equals(List.of("restored", "start"))
                && result.getLabels().getNamed().containsKey("new")
                && result.getLabels().getNamed().get("new") == null, "replayed typed values");
    }

    private static void check(boolean passed, String label) {
        if (!passed) throw new AssertionError(label);
    }
}
