package eu.mihosoft.vmf.jackson.test.story_graph_01.json_spec_test_01;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;

import eu.mihosoft.vmf.jackson.test.story_graph_01.*;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema generation for a deep, recursive model that mixes {@code @InterfaceOnly} base types
 * ({@code Update}, {@code Condition}) with concrete subtypes ({@code StateUpdate},
 * {@code StateCondition}). This is the case that was previously flagged as
 * "options about extending types not fully working in schema".
 */
public class SchemaTest01 {

    private JsonNode generateSchema() {
        var schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                .generateSchema(StoryNode.class);
        return new ObjectMapper().valueToTree(schema);
    }

    /** Collects the discriminator type names offered by a polymorphic {@code oneOf} node. */
    private static Set<String> discriminatorsOf(JsonNode oneOf) {
        Set<String> names = new HashSet<>();
        assertTrue(oneOf.isArray() && oneOf.size() > 0, "expected a non-empty oneOf");
        for (JsonNode option : oneOf) {
            JsonNode allOf = option.get("allOf");
            assertTrue(allOf != null && allOf.size() == 2,
                    "each polymorphic option must combine $ref + discriminator via allOf");
            assertTrue(allOf.get(0).has("$ref"), "first allOf branch must be the $ref");
            names.add(allOf.get(1).at("/properties/@vmf-type/enum/0").asText());
        }
        return names;
    }

    @Test
    void interfaceOnlyBaseTypesAreExcludedButConcreteSubtypesAreDefined() {
        JsonNode defs = generateSchema().get("definitions");
        assertTrue(defs.has(StoryNode.class.getName()), "StoryNode definition expected");
        assertTrue(defs.has(StateUpdate.class.getName()), "concrete StateUpdate must be defined");
        assertTrue(defs.has(StateCondition.class.getName()), "concrete StateCondition must be defined");
        // @InterfaceOnly types cannot be instantiated and must not get a definition.
        assertFalse(defs.has(Update.class.getName()), "interface-only Update must not be defined");
        assertFalse(defs.has(Condition.class.getName()), "interface-only Condition must not be defined");
    }

    @Test
    void polymorphicUpdatesAndConditionsOfferConcreteSubtypes() {
        JsonNode schema = generateSchema();
        assertTrue(discriminatorsOf(schema.at("/properties/onEntryUpdates/items/oneOf"))
                        .contains(StateUpdate.class.getName()),
                "onEntryUpdates must let the editor pick the concrete StateUpdate");
        assertTrue(discriminatorsOf(schema.at("/properties/onEntryConditions/items/oneOf"))
                        .contains(StateCondition.class.getName()),
                "onEntryConditions must let the editor pick the concrete StateCondition");
    }

    @Test
    void recursiveNodeReferenceIsGenerated() {
        JsonNode schema = generateSchema();
        // StoryNode has no subtypes, so nested nodes reference the StoryNode definition directly.
        assertTrue(schema.at("/properties/nodes/items/$ref").asText().endsWith(StoryNode.class.getName()),
                "recursive 'nodes' must reference the StoryNode definition");
    }
}
