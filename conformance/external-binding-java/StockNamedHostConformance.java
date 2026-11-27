package eu.mihosoft.vmf.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFDocumentFormat;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.jackson.test.external_types01.ExternalEnum;
import eu.mihosoft.vmf.jackson.test.external_types01.ExternalTypeModel01;
import eu.mihosoft.vmf.jackson.test.type_formats01.FormatModel01;
import eu.mihosoft.vmf.jackson.test.type_formats01.Pet;
import eu.mihosoft.vmf.jackson.test.type_formats01.PetType;
import eu.mihosoft.vmftest.complex.supplier.Item;
import eu.mihosoft.vmftest.complex.supplier.PurchaseOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class StockNamedHostConformance {
    private static final long ORDER_DATE = -2208988800123L;
    private static final long SHIP_DATE = Long.MAX_VALUE - 1;

    private StockNamedHostConformance() {}

    static void verify(boolean write, Path exchange, String producer) throws IOException {
        var enumModel = ExternalTypeModel01.newBuilder().withName("Model 01").withNameValue("Model 01 Value")
                .withEnumValue(ExternalEnum.SECOND).withTags("tag1", "tag2", "tag3").withValues(1, 2, 3).build();
        var petModel = FormatModel01.newBuilder().withPets(Pet.newBuilder().withName("unknown").build(),
                Pet.newBuilder().withName("known").withType(PetType.DOG).build()).build();
        var date = new Date(0);
        var order = PurchaseOrder.newBuilder().withDate(date)
                .withItems(Item.newBuilder().withShipDate(new Date(SHIP_DATE)).build()).build();
        date.setTime(ORDER_DATE);
        check(order.asReadOnly().getDate() == date, "The read-only model keeps the mutable Java Date");

        for (var format : VMFDocumentFormat.values()) {
            var mapper = mapper(format);
            var suffix = "." + format.name().toLowerCase(Locale.ROOT);
            var enumFile = exchange.resolve(producer + "stock-named-enum" + suffix);
            var petFile = exchange.resolve(producer + "stock-pet-types" + suffix);
            var orderFile = exchange.resolve(producer + "stock-supplier-date" + suffix);
            if (write) {
                mapper.writeValue(enumFile.toFile(), enumModel);
                mapper.writeValue(petFile.toFile(), petModel);
                mapper.writeValue(orderFile.toFile(), order);
            }
            var named = mapper.readValue(enumFile.toFile(), ExternalTypeModel01.class);
            check(named.getEnumValue() == ExternalEnum.SECOND && named.getTags().size() == 3
                    && named.getValues().get(2) == 3, "The stock enum and adjacent scalar lists retain their values");
            if (format == VMFDocumentFormat.XML) {
                check(Files.readString(enumFile).contains("<external-type-model-01"), "The original XML root alias is preserved");
            }
            var pets = mapper.readValue(petFile.toFile(), FormatModel01.class).getPets();
            check(pets.size() == 2 && pets.get(0).getType() == PetType.UNKNOWN && pets.get(1).getType() == PetType.DOG
                    && "#ffa500".equals(pets.get(0).getColor()), "Stock defaults retain their external names and color");
            var restored = mapper.readValue(orderFile.toFile(), PurchaseOrder.class);
            check(restored.getDate().getTime() == ORDER_DATE && restored.getItems().get(0).getShipDate().getTime() == SHIP_DATE,
                    "The external Date uses signed 64-bit Unix milliseconds");
        }
        verifySchemas(write, exchange, producer);
    }

    private static ObjectMapper mapper(VMFDocumentFormat format) {
        ObjectMapper mapper;
        switch (format) {
            case XML: mapper = new XmlMapper(); break;
            case YAML: mapper = new YAMLMapper(); break;
            default: mapper = new ObjectMapper(); break;
        }
        return mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("external-type-model-01", ExternalTypeModel01.class.getName())
                .withTypeAlias("external-enum", ExternalEnum.class.getName()));
    }

    private static void verifySchemas(boolean write, Path exchange, String producer) throws IOException {
        var mapper = VMFJsonSchemaGenerator.createObjectMapper();
        var named = exchange.resolve(producer + "stock-named-enum-schema.json").toFile();
        var format = exchange.resolve(producer + "stock-pet-types-schema.json").toFile();
        var order = exchange.resolve(producer + "stock-supplier-date-schema.json").toFile();
        if (write) {
            var schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                    .withTypeAlias("external-type-model-01", ExternalTypeModel01.class.getName())
                    .withTypeAlias("pet", Pet.class.getName());
            mapper.writeValue(named, schema.generateSchema(ExternalTypeModel01.class));
            mapper.writeValue(format, schema.generateSchema(FormatModel01.class));
            mapper.writeValue(order, schema.generateSchema(PurchaseOrder.class));
        }
        var namedSchema = mapper.readTree(named);
        check(enumNames(properties(namedSchema, "enumValue").path("enumValue")).equals(Set.of("FIRST", "SECOND", "THIRD")),
                "The external model schema lists the original enum names");

        var formatSchema = mapper.readTree(format);
        var pets = properties(formatSchema, "pets").path("pets");
        var pet = properties(formatSchema, "otherProperty");
        check("table".equals(pets.path("format").asText()) && pets.path("uniqueItems").asBoolean(),
                "The pet list keeps the table format and uniqueness annotation");
        check("UNKNOWN".equals(pet.path("type").path("default").asText())
                        && enumNames(pet.path("type")).equals(Set.of("DOG", "CAT", "BIRD", "FISH", "UNKNOWN")),
                "The pet schema retains its named enum and default");
        check("color".equals(pet.path("color").path("format").asText())
                        && "#ffa500".equals(pet.path("color").path("default").asText())
                        && "My Title".equals(pet.path("otherProperty").path("title").asText())
                        && "table".equals(pet.path("properties").path("format").asText())
                        && !pet.has("model"),
                "The schema retains color, nested format, injected metadata, and owning-only fields");

        var orderSchema = mapper.readTree(order);
        check(containsType(properties(orderSchema, "date").path("date"), "integer")
                        && containsType(properties(orderSchema, "shipDate").path("shipDate"), "integer"),
                "The supplier schema describes its epoch millisecond fields as integers");
    }

    private static JsonNode properties(JsonNode schema, String identifyingField) {
        if (schema.path("properties").has(identifyingField)) return schema.path("properties");
        for (var definition : schema.path("definitions")) {
            if (definition.path("properties").has(identifyingField)) return definition.path("properties");
        }
        throw new AssertionError("No schema properties for " + identifyingField);
    }

    private static boolean containsType(JsonNode schema, String type) {
        var declared = schema.path("type");
        if (type.equals(declared.asText())) return true;
        for (var alternative : declared) if (type.equals(alternative.asText())) return true;
        return false;
    }

    private static Set<String> enumNames(JsonNode schema) {
        var names = new LinkedHashSet<String>();
        for (var name : schema.findValue("enum")) names.add(name.asText());
        return names;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
