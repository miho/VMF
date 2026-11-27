package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.jackson.test.external_types02.ExternalTypeModel02;
import eu.mihosoft.vmf.jackson.test.mapjournal.Labels;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class StockPlainMapConformance {
    private static final String CHECKPOINT = "labels-without-plain-map";
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "stock.labels-map"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return "labels"; }
        @Override public String type(VObject object) { return "labels"; }
        @Override public VObject create(String type, String id) { return Labels.newInstance(); }
    };

    private StockPlainMapConformance() {}

    static void verify(boolean write, Path exchange, String producer) throws IOException {
        var maps = externalMaps();
        var labels = sampleLabels();
        verifyUnrecordableMap();
        var journal = write ? JournalRecorder.recordFrame(checkpoint(), BINDING, CHECKPOINT, 10,
                root -> root.getNames().put("added", "managed")) : null;
        for (var format : VMFDocumentFormat.values()) {
            var suffix = "." + format.name().toLowerCase(Locale.ROOT);
            for (boolean references : new boolean[] { false, true }) {
                verifySnapshots(write, exchange, producer, maps, labels, format, suffix, references);
            }
            var log = exchange.resolve(producer + "plain-map-boundary-journal" + suffix);
            if (write) Files.writeString(log, VMFJournalCodec.write(journal, format));
            verifyReplay(VMFJournalCodec.read(Files.readString(log), format));
        }
    }

    private static void verifySnapshots(boolean write, Path exchange, String producer, ExternalTypeModel02 maps,
                                        Labels labels, VMFDocumentFormat format, String suffix, boolean refs) throws IOException {
        var mapper = mapper(format, refs);
        var mapsFile = exchange.resolve(producer + "stock-external-maps-" + refs + suffix);
        var labelsFile = exchange.resolve(producer + "stock-plain-managed-maps-" + refs + suffix);
        var clearedFile = exchange.resolve(producer + "stock-cleared-plain-map-" + refs + suffix);
        if (write) {
            mapper.writeValue(mapsFile.toFile(), maps);
            mapper.writeValue(labelsFile.toFile(), labels);
            mapper.writeValue(clearedFile.toFile(), checkpoint());
        }
        boolean xml = format == VMFDocumentFormat.XML;
        var restored = mapper.readValue(mapsFile.toFile(), ExternalTypeModel02.class);
        var entries = restored.getObjectMap();
        check("01234".equals(restored.getStringMap().get("zip")) && restored.getStringMap().containsKey("missing")
                && (xml ? "".equals(restored.getStringMap().get("missing")) : restored.getStringMap().get("missing") == null),
                "The string map retains the original lexical value and member presence");
        check(entries.get("meta").equals(Map.of("inner", "x", "count", xml ? "7" : 7))
                && (xml ? "42".equals(entries.get("num")) && "true".equals(entries.get("flag"))
                        : Integer.valueOf(42).equals(entries.get("num")) && Boolean.TRUE.equals(entries.get("flag")))
                && (xml ? List.of("a", "b", "").equals(entries.get("tags")) && "a".equals(entries.get("single"))
                        : Arrays.asList("a", "b", null).equals(entries.get("tags")) && List.of("a").equals(entries.get("single"))),
                "The ordinary object map retains each format's original value policy");
        var restoredLabels = mapper.readValue(labelsFile.toFile(), Labels.class);
        check("01234".equals(restoredLabels.getExternal().get("zip"))
                && restoredLabels.getExternal().containsKey("missing")
                && "label".equals(restoredLabels.getNames().get("default"))
                && restoredLabels.getNames().containsKey("managed-null") && restoredLabels.getNames().get("managed-null") == null,
                "The ordinary external map and managed VMap retain their separate fields");
        var cleared = Files.readString(clearedFile);
        check(!cleared.contains(xml ? "<external>" : "external") && mapper.readValue(cleared, Labels.class).getExternal().isEmpty(),
                "Legacy snapshots omit a cleared ordinary map and loading reapplies its factory");
        if (xml) {
            check(Files.readString(mapsFile).contains("<external-type-model-02")
                            && Files.readString(labelsFile).contains("<external><zip>01234</zip>"),
                    "Ordinary map XML uses the configured root alias and direct keys");
        }
    }

    private static ExternalTypeModel02 externalMaps() {
        var model = ExternalTypeModel02.newInstance();
        var other = ExternalTypeModel02.newInstance();
        check(model.getObjectMap() != other.getObjectMap() && model.getStringMap() != other.getStringMap(),
                "Executable defaults allocate fresh host maps");
        model.getObjectMap().put("meta", Map.of("inner", "x", "count", 7));
        model.getObjectMap().put("num", 42);
        model.getObjectMap().put("flag", true);
        model.getObjectMap().put("tags", Arrays.asList("a", "b", null));
        model.getObjectMap().put("single", List.of("a"));
        model.getStringMap().put("zip", "01234");
        model.getStringMap().put("missing", null);
        return model;
    }

    private static Labels sampleLabels() {
        var labels = Labels.newInstance();
        var events = new ArrayList<String>();
        var listener = labels.vmf().changes().addListener(change -> events.add(change.propertyName()));
        try {
            labels.getExternal().put("zip", "01234");
            labels.getExternal().put("missing", null);
            check(events.isEmpty(), "Raw in-place mutation is outside VMF change tracking");
            check(labels.asReadOnly().getExternal() == labels.getExternal(), "The raw host stays live through the read-only model");
            labels.getNames().put("managed-null", null);
            check(events.equals(List.of("names")), "Managed map edits are observed");
        } finally {
            listener.unsubscribe();
        }
        return labels;
    }

    private static Labels checkpoint() {
        var labels = Labels.newInstance();
        labels.setExternal(null);
        return labels;
    }

    private static void verifyUnrecordableMap() {
        var unsupported = Labels.newInstance();
        try (var journal = JournalRecorder.start(unsupported, BINDING, CHECKPOINT, 10)) {
            throw new AssertionError("The plain map must fail journal preflight even when empty");
        } catch (IllegalArgumentException expected) {
            check(!unsupported.vmf().changes().isModelVersioningEnabled(), "Failed preflight leaves recording inactive");
        }
        var baseline = checkpoint();
        try (var recorder = JournalRecorder.start(baseline, BINDING, CHECKPOINT, 10)) {
            baseline.setExternal(new LinkedHashMap<>(Map.of("key", "value")));
            recorder.finish();
            throw new AssertionError("The plain map replacement must fail portable capture");
        } catch (IllegalStateException expected) {
            check(!baseline.vmf().changes().isModelVersioningEnabled(), "Failed capture closes the recording");
        }
    }

    private static void verifyReplay(ChangeJournal journal) {
        check(journal.wireVersion() == 1 && journal.transactions().size() == 1, "Managed map edits retain V1");
        var operations = journal.transactions().get(0).operations();
        check(operations.size() == 1 && operations.get(0).kind() == ChangeJournal.Kind.MAP_EDIT
                && operations.get(0).property().equals("names"), "Only the managed VMap produces a map record");
        var initial = checkpoint();
        var replay = JournalReplayer.replay(initial, BINDING, CHECKPOINT, journal);
        check(replay.getExternal() == null && "managed".equals(replay.getNames().get("added"))
                && "label".equals(replay.getNames().get("default")) && !initial.getNames().containsKey("added"),
                "Replay preserves the live checkpoint's explicit null raw map");
    }

    private static ObjectMapper mapper(VMFDocumentFormat format, boolean references) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper(); break;
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT).withTypeAlias("external-type-model-02", ExternalTypeModel02.class.getName()));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
