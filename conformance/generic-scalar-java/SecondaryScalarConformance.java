package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmf.conformance.genericscalars.ComposedLabels;
import eu.mihosoft.vmf.conformance.genericscalars.ComposedMeasurements;
import eu.mihosoft.vmf.conformance.genericscalars.DirectMeasurements;
import eu.mihosoft.vmf.conformance.genericscalars.ForwardValues;
import eu.mihosoft.vmf.conformance.genericscalars.ReadOnlySampleValues;
import eu.mihosoft.vmf.conformance.genericscalars.ReadingDocument;
import eu.mihosoft.vmf.conformance.genericscalars.SampleValues;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

final class SecondaryScalarConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "secondary-generic"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return ((ReadingDocument) model).getId(); }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalStateException("no allocations"); }
    };

    private SecondaryScalarConformance() { }

    static void verify(boolean write, Path directory) throws IOException {
        var baseline = measurements();
        var labels = ComposedLabels.newBuilder().withId("text").withLatest("false")
                .withHistory(Arrays.asList("7", null, "true")).build();
        ForwardValues<Integer> direct = DirectMeasurements.newInstance();
        direct.setLatest(11);
        check(direct.asReadOnly().getLatest() == 11, "direct secondary specialization");
        SampleValues<Integer> numeric = baseline;
        SampleValues<String> textual = labels;
        ReadOnlySampleValues<Integer> numericView = numeric.asReadOnly();
        ReadOnlySampleValues<String> textView = textual.asReadOnly();
        numeric.setLatest(41);
        textual.getHistory().set(0, "live");
        check(numericView.getLatest() == 41 && numericView.getHistory().get(1) == null
                && textView.getHistory().get(0).equals("live"), "closed live read-only projections");
        numeric.setLatest(null);
        textual.getHistory().set(0, "7");
        var field = baseline.vmf().reflect().propertyByName("history").orElseThrow().getType().getDescriptor();
        var model = ComposedMeasurements.type().superTypes().get(1).superTypes().get(0).superTypes().get(0).getDescriptor();
        check(field.getArguments().get(0).getName().equals(Integer.class.getName())
                && model.getArguments().get(0).getName().equals(Integer.class.getName()), "closed reflected arguments");
        var journal = write ? JournalRecorder.recordFrame(measurements(), BINDING, "initial", 4, modelValue -> {
            SampleValues<Integer> samples = modelValue;
            samples.setLatest(42);
            samples.getHistory().set(1, -3);
            samples.getHistory().add(null);
        }) : null;
        String prefix = write ? "java-secondary-" : "csharp-secondary-";
        for (var format : VMFDocumentFormat.values()) {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            for (boolean refs : new boolean[]{false, true}) {
                var mapper = mapper(format, refs);
                var numericFile = directory.resolve(prefix + "measurements-" + refs + extension);
                var labelsFile = directory.resolve(prefix + "labels-" + refs + extension);
                if (write) {
                    Files.writeString(numericFile, mapper.writeValueAsString(baseline));
                    Files.writeString(labelsFile, mapper.writeValueAsString(labels));
                }
                var copy = mapper.readValue(numericFile.toFile(), ComposedMeasurements.class);
                var text = mapper.readValue(labelsFile.toFile(), ComposedLabels.class);
                check(copy.getId().equals("numeric") && copy.getLatest() == null
                        && Arrays.asList(7, null, 9).equals(copy.getHistory()), "measurements snapshot");
                check(text.getId().equals("text") && text.getLatest().equals("false")
                        && Arrays.asList("7", null, "true").equals(text.getHistory()), "labels snapshot");
            }
            Path journalFile = directory.resolve(prefix + "journal" + extension);
            if (write) Files.writeString(journalFile, VMFJournalCodec.write(journal, format));
            var edits = VMFJournalCodec.read(Files.readString(journalFile), format);
            check(edits.wireVersion() == 1, "ordinary scalar and positional journal version");
            var checkpoint = mapper(format, true).readValue(
                    directory.resolve(prefix + "measurements-true" + extension).toFile(), ComposedMeasurements.class);
            var restored = JournalReplayer.replay(checkpoint, BINDING, "initial", edits);
            check(restored.getLatest() == 42 && Arrays.asList(7, -3, 9, null).equals(restored.getHistory())
                    && checkpoint.getLatest() == null && checkpoint.getHistory().get(1) == null, "replayed journal");
        }
        System.out.println("Java verified secondary generic snapshots, reflection, and journal from " + (write ? "Java." : "C#."));
    }

    private static ComposedMeasurements measurements() {
        return ComposedMeasurements.newBuilder().withId("numeric").withLatest(null).withHistory(Arrays.asList(7, null, 9)).build();
    }

    static ObjectMapper mapper(VMFDocumentFormat format, boolean references) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
