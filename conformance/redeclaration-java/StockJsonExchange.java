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
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.Array;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.ArrayValue;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.BooleanValue;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.NullValue;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.NumberValue;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.Obj;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.ObjectValue;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.StringValue;
import eu.mihosoft.vmftest.complex.vmf_text.generated.json.Val;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

final class StockJsonExchange {
    private static final JournalBinding VALUE = new JournalBinding() {
        @Override public String modelId() { return "stock-json-value"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject object) { return "value"; }
        @Override public String type(VObject object) { return NullValue.class.getName(); }
        @Override public VObject create(String type, String id) {
            if (!type.equals(NullValue.class.getName()) || !id.equals("value")) {
                throw new IllegalArgumentException("Unknown stock JSON value");
            }
            return NullValue.newInstance();
        }
    };

    private StockJsonExchange() { }

    static void verify(Path directory, boolean writing) throws IOException {
        var object = Obj.newInstance();
        var array = Array.newInstance();
        var objectValue = ObjectValue.newBuilder().withValue(object).build();
        var arrayValue = ArrayValue.newBuilder().withValue(array).build();
        check(((Val) objectValue).getValue() == object && ((Val) arrayValue).getValue() == array,
                "Stock branch getter retains reference identity");

        var baseline = NullValue.newBuilder().withValue(7).build();
        var journal = JournalRecorder.recordFrame(NullValue.newBuilder().withValue(7).build(), VALUE, "start", 0,
                value -> value.setValue("edited"));
        for (var format : VMFDocumentFormat.values()) {
            String producer = writing ? "java" : "csharp";
            String extension = "." + format.name().toLowerCase(Locale.ROOT);
            String prefix = producer + "-stock-json-";
            var mapper = mapper(format);
            verifyValue(mapper, directory.resolve(prefix + "text" + extension), writing,
                    StringValue.newBuilder().withValue("text").build(), StringValue.class, "text");
            verifyValue(mapper, directory.resolve(prefix + "number" + extension), writing,
                    NumberValue.newBuilder().withValue(5.0).build(), NumberValue.class, 5.0);
            verifyValue(mapper, directory.resolve(prefix + "boolean" + extension), writing,
                    BooleanValue.newBuilder().withValue(true).build(), BooleanValue.class, true);
            verifyValue(mapper, directory.resolve(prefix + "object" + extension), writing,
                    objectValue, ObjectValue.class, null);
            verifyValue(mapper, directory.resolve(prefix + "array" + extension), writing,
                    arrayValue, ArrayValue.class, null);
            verifyValue(mapper, directory.resolve(prefix + "null" + extension), writing,
                    baseline, NullValue.class, format == VMFDocumentFormat.XML ? "7" : 7);

            var journalPath = directory.resolve(prefix + "journal" + extension);
            if (writing) Files.writeString(journalPath, VMFJournalCodec.write(journal, format));
            var decoded = VMFJournalCodec.read(Files.readString(journalPath), format);
            var replayed = JournalReplayer.replay(baseline, VALUE, "start", decoded);
            check(decoded.wireVersion() == 1 && baseline.getValue().equals(7) && replayed.getValue().equals("edited"),
                    "Stock writable Object branch replays with its live boxed checkpoint");
        }
    }

    private static <T extends Val> void verifyValue(ObjectMapper mapper, Path file, boolean writing, T value,
                                                  Class<T> type, Object expected) throws IOException {
        if (writing) mapper.writeValue(file.toFile(), value);
        var restored = mapper.readValue(file.toFile(), type);
        check(Objects.equals(restored.getValue(), expected), "Stock JSON branch differs in " + file.getFileName());
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper(); break;
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withReferences(VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
