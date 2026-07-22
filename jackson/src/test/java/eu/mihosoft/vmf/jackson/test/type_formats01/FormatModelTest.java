package eu.mihosoft.vmf.jackson.test.type_formats01;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates that schema-customization annotations ({@code format}, {@code uniqueItems},
 * {@code inject}, default values, and enum handling) are reflected in the generated schema.
 * Previously this test only printed the schema without asserting anything.
 */
class FormatModelTest {

    private JsonNode generateSchema() {
        var schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("pet", Pet.class.getName())
                .generateSchema(FormatModel01.class);
        return new ObjectMapper().valueToTree(schema);
    }

    @Test
    void arrayFormatAndUniqueItemsAreApplied() {
        JsonNode schema = generateSchema();
        assertEquals("array", schema.at("/properties/pets/type").asText());
        assertEquals("table", schema.at("/properties/pets/format").asText(),
                "vmf:jackson:schema:format on the collection must be emitted");
        assertTrue(schema.at("/properties/pets/uniqueItems").asBoolean(),
                "vmf:jackson:schema:uniqueItems must be emitted");
        assertEquals("#/definitions/pet", schema.at("/properties/pets/items/$ref").asText());
    }

    @Test
    void colorFormatAndDefaultAreApplied() {
        JsonNode schema = generateSchema();
        assertEquals("color", schema.at("/definitions/pet/properties/color/format").asText());
        assertEquals("#ffa500", schema.at("/definitions/pet/properties/color/default").asText());
    }

    @Test
    void injectedSchemaFragmentIsMerged() {
        JsonNode schema = generateSchema();
        assertEquals("My Title", schema.at("/definitions/pet/properties/otherProperty/title").asText(),
                "vmf:jackson:schema:inject must merge raw JSON into the property schema");
    }

    @Test
    void externalEnumIsRenderedAsStringEnumWithDefault() {
        JsonNode schema = generateSchema();
        JsonNode type = schema.at("/definitions/pet/properties/type");
        assertEquals("string", type.at("/type").asText(), "an enum must be a string type");

        List<String> constants = new ArrayList<>();
        type.get("enum").forEach(n -> constants.add(n.asText()));
        assertTrue(constants.contains("UNKNOWN") && constants.contains("DOG"),
                "enum constants must be listed, got: " + constants);
        assertEquals("UNKNOWN", type.at("/default").asText(), "default enum constant must be emitted");
    }

    @Test
    void nestedContainedCollectionKeepsItsFormat() {
        JsonNode schema = generateSchema();
        assertEquals("array", schema.at("/definitions/pet/properties/properties/type").asText());
        assertEquals("table", schema.at("/definitions/pet/properties/properties/format").asText());
        // the container back-reference 'owner' on Prop and 'model' on Pet must be excluded
        assertTrue(schema.at("/definitions/pet/properties/model").isMissingNode());
    }
}
