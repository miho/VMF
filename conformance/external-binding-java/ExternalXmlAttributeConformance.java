package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import eu.mihosoft.vmf.conformance.externalbinding.Endpoint;
import eu.mihosoft.vmf.conformance.externalbinding.host.Distance;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

final class ExternalXmlAttributeConformance {
    private static final Distance MEASUREMENT = new Distance(9007199254740993L, " note & finish ", true);
    private static final String HOST_ID = "opaque-host-id";

    private ExternalXmlAttributeConformance() {}

    static void verify(boolean write, Path exchange, String producer) throws IOException {
        var model = Endpoint.newBuilder().withId("attribute-sample").withMeasurement(MEASUREMENT)
                .withHistory(MEASUREMENT, null, MEASUREMENT).build();
        for (boolean references : new boolean[]{false, true}) {
            var mapper = mapper(references);
            var snapshot = exchange.resolve(producer + "external-attributes-" + references + ".xml");
            if (write) mapper.writeValue(snapshot.toFile(), model);
            var restored = mapper.readValue(snapshot.toFile(), Endpoint.class);
            if (!"attribute-sample".equals(restored.getId()) || !MEASUREMENT.equals(restored.getMeasurement())
                    || !Arrays.asList(MEASUREMENT, null, MEASUREMENT).equals(restored.getHistory())) {
                throw new AssertionError("Structured host values retain their attributes, text and nullable slots");
            }
        }
    }

    private static XmlMapper mapper(boolean references) {
        var mapper = new XmlMapper();
        mapper.registerModule(new SimpleModule()
                .addSerializer(Distance.class, new JsonSerializer<Distance>() {
                    @Override public void serialize(Distance value, JsonGenerator generator, SerializerProvider context)
                            throws IOException {
                        var xml = (ToXmlGenerator) generator;
                        xml.writeStartObject();
                        xml.setNextIsAttribute(true);
                        xml.writeNumberField("millimetres", value.millimetres());
                        xml.writeBooleanField("estimated", value.estimated());
                        xml.writeStringField("vmf-id", HOST_ID);
                        xml.setNextIsAttribute(false);
                        xml.writeStringField("note", value.note());
                        xml.writeEndObject();
                    }
                })
                .addDeserializer(Distance.class, new JsonDeserializer<Distance>() {
                    @Override public Distance deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                        JsonNode node = parser.getCodec().readTree(parser);
                        if (!HOST_ID.equals(node.path("vmf-id").asText())) throw new IOException("Missing opaque host attribute");
                        return new Distance(node.get("millimetres").asLong(), node.get("note").asText(),
                                node.get("estimated").asBoolean());
                    }
                }))
                .registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withReferences(references ? VMFJacksonModule.ReferenceMode.IDS : VMFJacksonModule.ReferenceMode.OMIT));
        return mapper;
    }
}
