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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.math.BigInteger;

public final class LegacyObjectConformance {
    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "legacy-object"; }
        @Override public int modelVersion() { return 23; }
        @Override public String id(VObject value) { return "root"; }
        @Override public String type(VObject value) { return value.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new IllegalArgumentException("unexpected creation"); }
    };

    private LegacyObjectConformance() { }

    public static void verify(boolean write, Path directory) throws java.io.IOException {
        var values = values();
        for (var format : VMFDocumentFormat.values()) {
            var mapper = mapper(format);
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            for (var entry : values.entrySet()) {
                String name = entry.getKey();
                Object input = entry.getValue();
                var baseline = newRoot(input);
                String javaWire = mapper.writeValueAsString(baseline);
                var expected = mapper.readValue(javaWire, PayloadRecord.class).getPayload();
                Path snap = directory.resolve((write ? "java-" : "csharp-") + "legacy-object-" + name + suffix);
                if (write) Files.writeString(snap, javaWire);
                var actual = mapper.readValue(snap.toFile(), PayloadRecord.class).getPayload();
                if (!write && name.equals("exponent") && format == VMFDocumentFormat.XML) {
                    check(List.of("9.999999999999999E22", "1.0E23").contains(expected) && "1.0E23".equals(actual),
                            "The unchanged XML shape carries the host formatter text: " + expected + " / " + actual);
                } else check(Objects.equals(expected, actual), name + " " + format + " decoded payload shape");
                if (!(input instanceof Map) && !(input instanceof List) && !(input instanceof BigInteger)) {
                    var journal = JournalRecorder.recordFrame(newRoot(input), BINDING, "initial", 0,
                            node -> node.setPayload("next"));
                    Path log = directory.resolve((write ? "java-" : "csharp-") + "legacy-object-" + name + ".journal" + suffix);
                    if (write) Files.writeString(log, VMFJournalCodec.write(journal, format));
                    var decoded = VMFJournalCodec.read(Files.readString(log), format);
                    check(decoded.wireVersion() == 1
                        && VMFJournalCodec.write(decoded, VMFDocumentFormat.JSON).equals(VMFJournalCodec.write(journal, VMFDocumentFormat.JSON)),
                        "same V1 record for " + name);
                    check("next".equals(JournalReplayer.replay(baseline, BINDING, "initial", decoded).getPayload()), "in-memory replay " + name);
                    boolean replays = Objects.equals(input, actual);
                    try {
                        check("next".equals(JournalReplayer.replay(newRoot(actual), BINDING, "initial", decoded).getPayload()), "wire replay " + name);
                        check(replays, "accepted erased checkpoint " + name + " " + format);
                    } catch (IllegalArgumentException mismatch) {
                        check(!replays && mismatch.getMessage().contains("Old value differs"), mismatch.getMessage());
                    }
                } else {
                    try {
                        JournalRecorder.recordFrame(baseline, BINDING, "initial", 0, node -> node.setPayload("next"));
                        throw new AssertionError("unexpectedly journaled " + name);
                    } catch (IllegalArgumentException unsupported) {
                        check(unsupported.getMessage().contains("Unsupported journal value type"), unsupported.getMessage());
                    }
                }
            }
        }
        System.out.println("Java " + (write ? "write" : "read") + " verified " + values.size() + " legacy Java Object payloads in JSON/YAML/XML and the existing V1 replay boundaries.");
        UntypedListConformance.verify(write, directory);
    }

    private static PayloadRecord newRoot(Object value) {
        return PayloadRecord.newBuilder().withPayload(value).build();
    }

    private static Map<String, Object> values() {
        var values = new LinkedHashMap<String, Object>();
        values.put("nil", null);
        values.put("string", "true");
        values.put("boolean", true);
        values.put("char", 'A');
        values.put("byte", (byte) 2);
        values.put("short", (short) -3);
        values.put("int", 42);
        values.put("narrow-long", 2L);
        values.put("wide-long", 4000000000L);
        values.put("float", 3.5f);
        values.put("double", 1.0);
        values.put("negative-zero", -0.0);
        values.put("nan", Double.NaN);
        values.put("exponent", 1e23);
        values.put("wide-integer", new BigInteger("9223372036854775809"));
        var map = new LinkedHashMap<String, Object>();
        map.put("empty", "");
        map.put("nil", null);
        map.put("floats", Arrays.asList(1.0, null, ""));
        map.put("nested", Map.of("enabled", true));
        map.put("emptyArray", List.of());
        map.put("emptyMap", Map.of());
        values.put("map", map);
        values.put("list", List.of(List.of(1,2), List.of(3)));
        values.put("singleton-list", List.of(true));
        return values;
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper result;
        switch (format) {
            case YAML: result = new YAMLMapper(); break;
            case XML: result = new XmlMapper(); break;
            default: result = new ObjectMapper();
        }
        return result.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static void check(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }
}
