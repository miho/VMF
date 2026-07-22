package eu.mihosoft.vmf.jackson.test.simple;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the structure of the JSON schema generated for a polymorphic model
 * ({@link Person} with subtype {@link Employee}, plus the immutable {@link Address}).
 *
 * <p>Previously this test only printed the schema and asserted nothing; it now checks the
 * concrete draft-07 structure, including the polymorphic {@code oneOf}/{@code allOf}
 * discriminator, default values, numeric constraints and property ordering.</p>
 */
public class JsonSchemaModelTest {

    private JsonNode generateSchema() {
        var schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .withTypeAlias("person", Person.class.getName())
                .withTypeAlias("employee", Employee.class.getName())
                .withTypeAlias("my-model", MyModel.class.getName())
                .generateSchema(MyModel.class);
        return new ObjectMapper().valueToTree(schema);
    }

    @Test
    void schemaHasDraft07Header() {
        JsonNode schema = generateSchema();
        assertEquals("http://json-schema.org/draft-07/schema#", schema.get("$schema").asText());
        assertEquals("object", schema.get("type").asText());
        assertEquals("MyModel", schema.get("title").asText());
    }

    @Test
    void personsIsAnArrayWithPreservedOrder() {
        JsonNode schema = generateSchema();
        assertEquals("array", schema.at("/properties/persons/type").asText(),
                "'persons' must be modelled as an array");
        assertTrue(schema.at("/properties/persons/propertyOrder").isInt(),
                "property order must be emitted so editors keep declaration order");
    }

    @Test
    void containerBackReferenceIsNotSerialized() {
        JsonNode schema = generateSchema();
        // 'model' is the container back-reference on Person and must be excluded from the schema.
        assertTrue(schema.at("/definitions/person/properties/model").isMissingNode(),
                "container back-reference 'model' must not appear in the schema");
    }

    @Test
    void polymorphicItemsUseOneOfWithAllOfDiscriminator() {
        JsonNode schema = generateSchema();
        JsonNode oneOf = schema.at("/properties/persons/items/oneOf");
        assertTrue(oneOf.isArray(), "polymorphic items must use oneOf");
        assertEquals(2, oneOf.size(), "oneOf must offer Person and Employee");

        Set<String> discriminators = new HashSet<>();
        for (JsonNode option : oneOf) {
            JsonNode allOf = option.get("allOf");
            assertTrue(allOf != null && allOf.isArray() && allOf.size() == 2,
                    "each option must combine a $ref and a discriminator via allOf");

            // draft-07 ignores keywords that sit next to $ref, so the discriminator must NOT be
            // a sibling of $ref -- it must live in its own allOf branch.
            assertTrue(allOf.get(0).has("$ref"), "first allOf branch must be the $ref");
            assertFalse(allOf.get(0).has("required"),
                    "discriminator must not be a sibling of $ref (draft-07 would ignore it)");

            String ref = allOf.get(0).get("$ref").asText();
            assertTrue(ref.startsWith("#/definitions/"), "ref must point into definitions: " + ref);

            JsonNode discriminator = allOf.get(1);
            assertEquals("@vmf-type", discriminator.get("required").get(0).asText());
            String typeName = discriminator.at("/properties/@vmf-type/enum/0").asText();
            discriminators.add(typeName);
            // the discriminator's fixed value must match the referenced definition
            assertEquals("#/definitions/" + typeName, ref,
                    "discriminator value must match the referenced definition");
        }
        assertEquals(Set.of("person", "employee"), discriminators,
                "both concrete subtypes must be selectable with their alias as discriminator");
    }

    @Test
    void definitionsCarryDefaultsAndConstraints() {
        JsonNode schema = generateSchema();

        assertTrue(schema.at("/definitions/person").isObject(), "person definition expected");
        assertTrue(schema.at("/definitions/employee").isObject(), "employee definition expected");

        // Person.age: @DefaultValue("30") + constraints minimum=0 / maximum=99
        assertEquals("integer", schema.at("/definitions/person/properties/age/type").asText());
        assertEquals(30, schema.at("/definitions/person/properties/age/default").asInt());
        assertEquals(0, schema.at("/definitions/person/properties/age/minimum").asInt());
        assertEquals(99, schema.at("/definitions/person/properties/age/maximum").asInt());

        // Employee adds employeeId
        assertTrue(schema.at("/definitions/employee/properties/employeeId").isObject(),
                "employee-specific property must be present");
    }
}
