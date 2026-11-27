package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.conformance.externalbinding.Endpoint;
import eu.mihosoft.vmf.conformance.externalbinding.ModeProfile;
import eu.mihosoft.vmf.conformance.externalbinding.PresetEndpoint;
import eu.mihosoft.vmf.conformance.externalbinding.TextBuffers;
import eu.mihosoft.vmf.conformance.externalbinding.host.Distance;
import eu.mihosoft.vmf.conformance.externalbinding.host.DispatchMode;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJournalCodec;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal;
import eu.mihosoft.vmf.runtime.core.journal.JournalBinding;
import eu.mihosoft.vmf.runtime.core.journal.JournalRecorder;
import eu.mihosoft.vmf.runtime.core.journal.JournalReplayer;
import eu.mihosoft.vmf.runtime.core.journal.JournalValueCodecs;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

final class ExternalBindingConformance {
    private static final URI LOCATION = URI.create("https://Example.invalid:443/a/%2f/../b?x=%e2%82%ac#first");
    private static final URI RELATIVE = URI.create("../catalog%2F?q=false#relative");
    private static final URI EMPTY = URI.create("");
    private static final URI UPDATED = URI.create(LOCATION.toString().replace("#first", "#second"));
    private static final URI ADDED = URI.create("urn:sample:item");
    private static final Distance DISTANCE = new Distance(9007199254740993L, "true", false);
    private static final Distance EDITED = new Distance(7, "note", true);
    private static final String HOST_TYPE = "warehouse-distance/v1";
    private static final String HOST_ID = "repeated-host-id";
    private static final JournalValueCodecs CODECS = JournalValueCodecs.standard()
            .withImmutable("probe.uri/v1", URI.class, URI::toString, URI::create)
            .withImmutable("probe.distance/v1", Distance.class, ExternalBindingConformance::encodeDistance,
                    ExternalBindingConformance::decodeDistance);

    private static final JournalBinding BINDING = new JournalBinding() {
        @Override public String modelId() { return "external-binding"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return ((Endpoint) model).getId(); }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { return Endpoint.newBuilder().withId(id).build(); }
        @Override public JournalValueCodecs valueCodecs() { return CODECS; }
    };

    private static final JournalBinding MODE_BINDING = new JournalBinding() {
        private final JournalValueCodecs codecs = JournalValueCodecs.standard()
                .withImmutable("probe.dispatch-mode/v1", DispatchMode.class, DispatchMode::name, DispatchMode::valueOf);

        @Override public String modelId() { return "enum-profile"; }
        @Override public int modelVersion() { return 1; }
        @Override public String id(VObject model) { return "profile"; }
        @Override public String type(VObject model) { return model.vmf().reflect().type().getName(); }
        @Override public VObject create(String type, String id) { throw new UnsupportedOperationException(); }
        @Override public JournalValueCodecs valueCodecs() { return codecs; }
    };

    private ExternalBindingConformance() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("write") || args[0].equals("read"))) {
            throw new IllegalArgumentException("Expected (write|read) <directory>");
        }
        boolean write = args[0].equals("write");
        Path exchange = Path.of(args[1]);
        if (write) Files.createDirectories(exchange);
        String producer = write ? "java-" : "csharp-";
        ChangeJournal journal = write ? record() : null;
        for (var format : VMFDocumentFormat.values()) {
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            for (boolean references : new boolean[] {false, true}) {
                var mapper = mapper(format, references);
                var snapshot = exchange.resolve(producer + "external-" + references + suffix);
                if (write) Files.writeString(snapshot, mapper.writeValueAsString(fixture()));
                verifySnapshot(mapper.readValue(snapshot.toFile(), Endpoint.class));
            }
            Path log = exchange.resolve(producer + "journal" + suffix);
            if (write) Files.writeString(log, VMFJournalCodec.write(journal, format));
            ChangeJournal restored = VMFJournalCodec.read(Files.readString(log), format);
            check(restored.wireVersion() == 1, "The bound external values use journal version 1.");
            var replayed = JournalReplayer.replay(fixture(), BINDING, "start", restored);
            check(UPDATED.toString().equals(replayed.getLocation().toString()) && replayed.getLinks().size() == 4
                    && ADDED.equals(replayed.getLinks().get(3)) && EDITED.equals(replayed.getMeasurement()),
                    "Both host values survive journal replay.");
        }
        System.out.println("Java verified " + (write ? "its own" : "C#") + " URI and structured external snapshots and external value journals.");
        verifyEnumBinding(write, exchange);
        verifyClearedHostDefaults(write, exchange, producer);
        verifyHostMetadata(write, exchange, producer);
        ExternalXmlAttributeConformance.verify(write, exchange, producer);
        GenericEnumConformance.verify(write, exchange);
        SecondaryExternalConformance.verify(write, exchange, mapper(VMFDocumentFormat.JSON, false));
        verifyTextBuffers(write, exchange, producer);
        StockExternalBindingConformance.verify(write, exchange, producer);
        StockRawExternalTypesConformance.verify(write, exchange, producer);
        StockNamedHostConformance.verify(write, exchange, producer);
        StockDevComConformance.verify(write, exchange, producer);
        StockPlainMapConformance.verify(write, exchange, producer);
    }

    private static void verifyHostMetadata(boolean write, Path exchange, String producer) throws IOException {
        var example = Endpoint.newBuilder().withId("metadata").withMeasurement(DISTANCE)
                .withHistory(DISTANCE, null, DISTANCE).build();
        for (var format : new VMFDocumentFormat[]{VMFDocumentFormat.JSON, VMFDocumentFormat.YAML}) {
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            for (boolean references : new boolean[]{false, true}) {
                var mapper = mapper(format, references, true);
                var file = exchange.resolve(producer + "external-metadata-" + references + suffix);
                if (write) Files.writeString(file, mapper.writeValueAsString(example));
                var restored = mapper.readValue(file.toFile(), Endpoint.class);
                check("metadata".equals(restored.getId()) && DISTANCE.equals(restored.getMeasurement())
                        && Arrays.asList(DISTANCE, null, DISTANCE).equals(restored.getHistory()),
                        "Declared structured values retain host metadata and their nullable list slots");

                var invalid = mapper.createObjectNode().put("@vmf-type", HOST_TYPE);
                try {
                    mapper.readValue(mapper.writeValueAsString(invalid), Endpoint.class);
                    throw new AssertionError("External metadata must still be rejected as a model type name");
                } catch (JsonMappingException expected) {
                    check(expected.getMessage().contains(HOST_TYPE), "A model discriminator still uses VMF type resolution");
                }
            }
        }
    }

    private static void verifyClearedHostDefaults(boolean write, Path exchange, String producer) throws IOException {
        var cleared = PresetEndpoint.newInstance();
        check(cleared.getKey() != null && cleared.getLocation() != null, "Host constructor defaults are initialized");
        cleared.setKey(null);
        cleared.setLocation(null);
        for (var format : VMFDocumentFormat.values()) {
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            String omitted = format == VMFDocumentFormat.XML ? "<" + PresetEndpoint.class.getName() + "/>" : "{}";
            for (boolean references : new boolean[]{false, true}) {
                var mapper = mapper(format, references);
                var path = exchange.resolve(producer + "cleared-host-defaults-" + references + suffix);
                if (write) Files.writeString(path, mapper.writeValueAsString(cleared));
                var restored = mapper.readValue(path.toFile(), PresetEndpoint.class);
                check(restored.getLocation() == null && restored.getKey() == null,
                        "Explicitly cleared host defaults stay null in " + format);
                var defaults = mapper.readValue(omitted, PresetEndpoint.class);
                check(defaults.getLocation() != null && defaults.getKey() != null,
                        "Omitted external fields still initialize their defaults in " + format);
            }
        }
    }

    private static void verifyTextBuffers(boolean write, Path exchange, String producer) throws IOException {
        var text = new StringBuilder("start");
        var model = TextBuffers.newBuilder().withTitle(text).withLines(text, null).build();
        var view = model.asReadOnly();
        check(view.getTitle() == text && view.getLines().get(0) == text, "Native views retain mutable host aliases");
        text.append(" & end");
        check(view.getTitle().toString().equals("start & end"), "The ordinary host value stays live");
        for (var format : VMFDocumentFormat.values()) {
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            for (boolean refs : new boolean[]{false, true}) {
                var io = mapper(format, refs);
                var path = exchange.resolve(producer + "text-buffers-" + refs + suffix);
                if (write) Files.writeString(path, io.writeValueAsString(model));
                var read = io.readValue(path.toFile(), TextBuffers.class);
                check(read.getTitle().toString().equals("start & end") && read.getLines().size() == 2
                        && read.getLines().get(0).toString().equals("start & end") && read.getLines().get(1) == null,
                        "Snapshot copies external text content and the explicit null slot");
            }
        }
        System.out.println("Java verified the mutable host snapshots from " + (write ? "Java" : "C#") + ".");
    }

    private static void verifyEnumBinding(boolean write, Path exchange) throws IOException {
        var baseline = modeProfile();
        String producer = write ? "java-" : "csharp-";
        var journal = write ? JournalRecorder.recordFrame(modeProfile(), MODE_BINDING, "initial", 0, model -> {
            model.setPreferred(DispatchMode.ARCHIVED);
            model.getHistory().add(DispatchMode.UNDER_REVIEW);
        }) : null;
        for (var format : VMFDocumentFormat.values()) {
            String suffix = "." + format.name().toLowerCase(Locale.ROOT);
            for (boolean references : new boolean[]{false, true}) {
                var io = mapper(format, references);
                var document = exchange.resolve(producer + "mode-" + references + suffix);
                if (write) Files.writeString(document, io.writeValueAsString(baseline));
                var typed = io.readValue(document.toFile(), ModeProfile.class);
                check(typed.getPreferred() == null && typed.getHistory().size() == 3
                        && typed.getHistory().get(0) == DispatchMode.UNDER_REVIEW
                        && typed.getHistory().get(1) == null && typed.getHistory().get(2) == DispatchMode.READY,
                        "nullable enum values retain their declared names");
            }
            Path log = exchange.resolve(producer + "mode-journal" + suffix);
            if (write) Files.writeString(log, VMFJournalCodec.write(journal, format));
            var read = VMFJournalCodec.read(Files.readString(log), format);
            check(read.wireVersion() == 1, "enum codec uses existing external-value records");
            var restored = JournalReplayer.replay(baseline, MODE_BINDING, "initial", read);
            check(restored.getPreferred() == DispatchMode.ARCHIVED && restored.getHistory().get(3) == DispatchMode.UNDER_REVIEW,
                    "enum journal retains exact type and values");
        }
        check(mapper(VMFDocumentFormat.JSON, false).readValue("{}", ModeProfile.class).getPreferred() == DispatchMode.READY,
                "absent enum field uses the declared Java default");
        System.out.println("Java verified native enum snapshots and external journal values.");
    }

    private static ModeProfile modeProfile() {
        return ModeProfile.newBuilder().withPreferred(null)
                .withHistory(DispatchMode.UNDER_REVIEW, null, DispatchMode.READY).build();
    }

    private static Endpoint fixture() {
        return Endpoint.newBuilder().withId("root").withLocation(LOCATION).withLinks(RELATIVE, null, EMPTY)
                .withMeasurement(DISTANCE).withHistory(DISTANCE, null, DISTANCE).build();
    }

    private static void verifySnapshot(Endpoint model) {
        check("root".equals(model.getId()) && LOCATION.toString().equals(model.getLocation().toString())
                && model.getLinks().size() == 3 && RELATIVE.toString().equals(model.getLinks().get(0).toString())
                && model.getLinks().get(1) == null && EMPTY.equals(model.getLinks().get(2)),
                "URI spellings, empty URI and null list slots survive.");
        check(DISTANCE.equals(model.getMeasurement()) && model.getHistory().size() == 3
                && DISTANCE.equals(model.getHistory().get(0)) && model.getHistory().get(1) == null
                && DISTANCE.equals(model.getHistory().get(2)), "Structured values preserve their types and widths.");
    }

    private static ChangeJournal record() {
        var model = fixture();
        try (var recorder = JournalRecorder.start(model, BINDING, "start", 0)) {
            model.setLocation(UPDATED);
            model.getLinks().add(ADDED);
            model.setMeasurement(EDITED);
            return recorder.finish();
        }
    }

    private static String encodeDistance(Distance value) {
        return value.millimetres() + "|" + value.note() + "|" + value.estimated();
    }

    private static Distance decodeDistance(String text) {
        var values = text.split("\\|", -1);
        return new Distance(Long.parseLong(values[0]), values[1], Boolean.parseBoolean(values[2]));
    }

    static ObjectMapper mapper(VMFDocumentFormat format, boolean references) {
        return mapper(format, references, false);
    }

    private static ObjectMapper mapper(VMFDocumentFormat format, boolean references, boolean hostMetadata) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper();
        }
        return mapper.registerModule(distanceModule(hostMetadata)).registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
    }

    private static SimpleModule distanceModule(boolean hostMetadata) {
        return new SimpleModule()
                .addSerializer(Distance.class, new JsonSerializer<Distance>() {
                    @Override public void serialize(Distance value, JsonGenerator json, SerializerProvider context)
                            throws IOException {
                        json.writeStartObject();
                        if (hostMetadata) {
                            json.writeStringField("@vmf-type", HOST_TYPE);
                            json.writeStringField("@vmf-id", HOST_ID);
                        }
                        json.writeNumberField("millimetres", value.millimetres());
                        json.writeStringField("note", value.note());
                        json.writeBooleanField("estimated", value.estimated());
                        json.writeEndObject();
                    }
                })
                .addDeserializer(Distance.class, new JsonDeserializer<Distance>() {
                    @Override public Distance deserialize(JsonParser json, DeserializationContext context)
                            throws IOException {
                        JsonNode node = json.getCodec().readTree(json);
                        if (hostMetadata && (!HOST_TYPE.equals(node.path("@vmf-type").asText())
                                || !HOST_ID.equals(node.path("@vmf-id").asText()))) {
                            throw new IOException("Expected the declared host value metadata");
                        }
                        return new Distance(node.path("millimetres").asLong(), node.path("note").asText(),
                                node.path("estimated").asBoolean());
                    }
                });
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
