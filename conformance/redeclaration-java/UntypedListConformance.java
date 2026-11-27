package eu.mihosoft.vmf.conformance.redeclarations;

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

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class UntypedListConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "untyped-list"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject value) { return "root"; }
        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new AssertionError("Unexpected creation"); }
    };
    private static final List<Object> JOURNAL_INITIAL = Arrays.asList((byte) 2, (short) -3, 1L, -0.0f, null);
    private static final List<Object> JOURNAL_RESULT = List.of('A', true, (short) -3, 1L, -0.0d);

    private UntypedListConformance() {}

    static void verify(boolean write, Path directory) throws IOException {
        verifyValueLookup();
        var source = model(values(true));
        var view = source.asReadOnly();
        check(view.getItems().size() == 9 && view.getItems().get(0) == null, "Live read-only list");
        source.getItems().add("tail");
        check(view.getItems().get(9).equals("tail"), "Read-only sees the new slot");
        source.getItems().remove(9);
        var target = model(List.of("old"));
        PayloadList.newBuilder().appendCollections(false).applyFrom(source).applyTo(target);
        check(target.getItems().equals(source.getItems()), "Builder replacement copies ordered slots");

        var journal = JournalRecorder.recordFrame(model(JOURNAL_INITIAL), BINDING, "initial", 0, root -> {
            root.getItems().set(0, 'A');
            root.getItems().set(3, -0.0d);
            root.getItems().add(1, true);
            root.getItems().remove(5);
        });
        for (var format : VMFDocumentFormat.values()) {
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            Path path = directory.resolve((write ? "java" : "csharp") + "-untyped-list.journal" + extension);
            if (write) Files.writeString(path, VMFJournalCodec.write(journal, format));
            var received = VMFJournalCodec.read(Files.readString(path), format);
            check(received.wireVersion() == 1 && VMFJournalCodec.write(received).equals(VMFJournalCodec.write(journal)),
                    "Unchanged V1 indexed edits and exact scalar tags");
            var checkpoint = model(JOURNAL_INITIAL);
            check(JournalReplayer.replay(checkpoint, BINDING, "initial", received).getItems().equals(JOURNAL_RESULT)
                            && checkpoint.getItems().equals(JOURNAL_INITIAL),
                    "V1 preserves nullable and boxed slots without altering the checkpoint");
            if (format == VMFDocumentFormat.JSON) {
                var json = mapper(format, true);
                var reboxed = json.readValue(json.writeValueAsString(checkpoint), PayloadList.class);
                check(reboxed.getItems().get(0) instanceof Integer && reboxed.getItems().get(3) instanceof Double,
                        "Legacy snapshots reconstruct numerics from their tokens");
                try {
                    JournalReplayer.replay(reboxed, BINDING, "initial", received);
                    throw new AssertionError("A differently boxed checkpoint must fail the V1 old-value check");
                } catch (IllegalArgumentException failure) {
                    check(failure.getMessage().contains("List precondition differs"), failure.getMessage());
                }
            }
            for (boolean references : List.of(false, true)) {
                boolean xml = format == VMFDocumentFormat.XML;
                var mapper = mapper(format, references);
                String profile = references ? "references" : "values";
                Path snapshot = directory.resolve((write ? "java" : "csharp") + "-untyped-list-" + profile + extension);
                var original = model(values(!xml));
                if (write) mapper.writeValue(snapshot.toFile(), original);
                var restored = mapper.readValue(snapshot.toFile(), PayloadList.class);
                if (xml) {
                    check(restored.getItems().equals(Arrays.asList(null, "1", "-0.0", "9007199254740993", "value", "false", "")),
                            "Legacy XML preserves null and projects other scalars to text");
                } else {
                    check(original.vmf().content().equals(restored)
                                    && original.vmf().content().hashCode() == restored.vmf().content().hashCode()
                                    && original.getItems().equals(restored.getItems()),
                            "Untyped JSON/YAML preserves boxed values and nested structures");
                    restored.getItems().set(2, 0d);
                    check(!original.vmf().content().equals(restored), "Content equality distinguishes signed zero");
                }
            }
        }
        verifyJsonCheckpoint(write, directory);
        try {
            JournalRecorder.recordFrame(model(List.of()), BINDING, "initial", 0,
                    root -> root.getItems().add(Map.of("nested", true)));
            throw new AssertionError("Unexpected built-in V1 scalar representation for a structured element");
        } catch (IllegalStateException failure) {
            check(failure.getCause() instanceof IllegalArgumentException
                            && failure.getCause().getMessage().contains("Unsupported journal value type"),
                    failure.toString());
        }
        System.out.println("Java verified untyped ordinary list snapshots and exact V1 scalar edits.");
    }

    private static void verifyJsonCheckpoint(boolean write, Path directory) throws IOException {
        var mapper = mapper(VMFDocumentFormat.JSON, true);
        var initial = Arrays.<Object>asList(1, 9007199254740993L, -0.0d, null);
        String producer = write ? "java-" : "csharp-";
        var snapshot = directory.resolve(producer + "untyped-list-checkpoint.json");
        var journalFile = directory.resolve(producer + "untyped-list-checkpoint.journal.json");
        if (write) {
            mapper.writeValue(snapshot.toFile(), model(initial));
            var working = mapper.readValue(snapshot.toFile(), PayloadList.class);
            var journal = JournalRecorder.recordFrame(working, BINDING, "canonical-json", 0, root -> {
                root.getItems().set(0, "after");
                root.getItems().set(1, 9007199254740994L);
                root.getItems().set(2, 0d);
                root.getItems().remove(3);
            });
            Files.writeString(journalFile, VMFJournalCodec.write(journal));
        }
        var checkpoint = mapper.readValue(snapshot.toFile(), PayloadList.class);
        var journal = VMFJournalCodec.read(Files.readString(journalFile), VMFDocumentFormat.JSON);
        var replay = JournalReplayer.replay(checkpoint, BINDING, "canonical-json", journal);
        check(journal.wireVersion() == 1 && replay.getItems().equals(List.of("after", 9007199254740994L, 0d))
                        && checkpoint.getItems().equals(initial),
                "Canonical INT/LONG/DOUBLE/null checkpoint replays after independent JSON deserialization");
    }

    private static void verifyValueLookup() {
        var map = Map.of("members", List.of(1, -0.0d));
        var nested = List.of("value", List.of(false));
        var source = model(Arrays.asList(map, nested, -0.0d, 0d, 1, 1L));
        var view = source.asReadOnly().getItems();
        var equalMap = Map.of("members", List.of(1, -0.0d));
        var equalList = List.of("value", List.of(false));
        check(equalMap != map && equalList != nested && view.contains(equalMap) && view.indexOf(equalList) == 1,
                "Read-only list lookup compares separately allocated nested values");
        check(view.indexOf(-0.0d) == 2 && view.indexOf(0d) == 3
                        && view.indexOf(1) == 4 && view.indexOf(1L) == 5 && !view.contains(1.0d),
                "Untyped lookup distinguishes signed zero and scalar boxing");
        check(source.getItems().remove(equalMap) && source.getItems().remove(equalList) && view.size() == 4,
                "Ordinary removal finds the first equal structural element");
    }

    private static PayloadList model(List<Object> items) {
        return PayloadList.newBuilder().withItems(items).build();
    }

    private static List<Object> values(boolean nested) {
        var values = new ArrayList<Object>(Arrays.asList(null, 1, -0.0d, 9007199254740993L, "value", false, ""));
        if (nested) {
            values.add(new BigInteger("9223372036854775809"));
            var map = new LinkedHashMap<String, Object>();
            map.put("@vmf-id", "plain map data");
            map.put("@vmf-type", "plain map data");
            map.put("members", Arrays.asList("note", null, Map.of("valid", true), List.of(4, -0.0d)));
            values.add(map);
        }
        return values;
    }

    private static ObjectMapper mapper(VMFDocumentFormat format, boolean references) {
        ObjectMapper mapper = format == VMFDocumentFormat.XML ? new XmlMapper()
                : format == VMFDocumentFormat.YAML ? new YAMLMapper() : new ObjectMapper();
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(references));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
