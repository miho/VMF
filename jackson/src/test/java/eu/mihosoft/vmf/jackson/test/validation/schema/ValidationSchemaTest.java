package eu.mihosoft.vmf.jackson.test.validation.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.VObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationSchemaTest {
    private static final VMFJacksonModule.RUNTIME_TYPE MODE = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
    private static final String VALID_DOCUMENT = "{\"entries\":[{\"entry-name\":\"A1\"}],"
            + "\"attributes\":{\"color\":\"red\"},\"labels\":[{\"key\":1,\"value\":\"first\"}]}";
    private final ObjectMapper mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(MODE));
    private final ModelValidator validator = ModelValidator.builder().build();

    @Test
    void wirePresenceUsesRenamesAndActualBuilderDefaults() throws IOException {
        var schema = schema(VMFJsonSchemaGenerator.newInstance(MODE), Document.class);
        var valid = (ObjectNode) mapper.readTree(VALID_DOCUMENT);
        assertValid(schema, valid);
        Document model = mapper.treeToValue(valid, Document.class);
        assertTrue(validator.validate(model).isValid());
        assertEquals("draft", model.getStatus());
        assertEquals(0, model.getRevision());

        for (String field : List.of("entries", "attributes", "labels")) {
            var missing = valid.deepCopy();
            missing.remove(field);
            assertInvalid(schema, missing);
            assertFalse(validator.validate(mapper.treeToValue(missing, Document.class)).isValid());
        }
        var missingName = valid.deepCopy();
        ((ObjectNode) missingName.at("/entries/0")).remove("entry-name");
        assertInvalid(schema, missingName);
        assertFalse(validator.validate(mapper.treeToValue(missingName, Document.class)).isValid());

        var disabled = valid.deepCopy();
        disabled.putNull("disabled");
        assertValid(schema, disabled);
        disabled.put("disabled", "present");
        assertInvalid(schema, disabled);
        assertFalse(validator.validate(mapper.treeToValue(disabled, Document.class)).isValid());
    }

    @Test
    void boundedLegacyArraysCountTargetsAfterDecoding() throws IOException {
        var schema = schema(VMFJsonSchemaGenerator.newInstance(MODE), Document.class);
        var valid = (ObjectNode) mapper.readTree(VALID_DOCUMENT);
        var nullable = valid.deepCopy();
        nullable.withArray("entries").addNull().addNull();
        assertValid(schema, nullable);
        assertTrue(validator.validate(mapper.treeToValue(nullable, Document.class)).isValid());

        var nullOnly = valid.deepCopy();
        nullOnly.putArray("entries").addNull();
        assertInvalid(schema, nullOnly);
        assertFalse(validator.validate(mapper.treeToValue(nullOnly, Document.class)).isValid());

        var tooManyTargets = valid.deepCopy();
        tooManyTargets.withArray("entries").addObject().put("entry-name", "B2");
        tooManyTargets.withArray("entries").addObject().put("entry-name", "C3");
        assertValid(schema, tooManyTargets);
        assertFalse(validator.validate(mapper.treeToValue(tooManyTargets, Document.class)).isValid());
    }

    @Test
    void oppositeCanFillOmittedEndOrAddToExplicitLegacyList() throws IOException {
        var schema = schema(VMFJsonSchemaGenerator.newInstance(MODE).withReferences(true), ReferenceDocument.class);
        var referenceMapper = new ObjectMapper()
                .registerModule(VMFJacksonModule.newInstance(MODE).withReferences(true));
        var payload = (ObjectNode) mapper.readTree("{\"stations\":[{\"@vmf-id\":\"s\",\"supervisor\":\"w\"}],"
                + "\"workers\":[{\"@vmf-id\":\"w\",\"station\":\"s\"}]}");
        assertValid(schema, payload);
        assertTrue(validator.validate(referenceMapper.treeToValue(payload, ReferenceDocument.class)).isValid());

        var station = (ObjectNode) payload.at("/stations/0");
        station.putArray("workers").addNull();
        assertValid(schema, payload);
        assertTrue(validator.validate(referenceMapper.treeToValue(payload, ReferenceDocument.class)).isValid());

        station.putArray("workers").add("w");
        var worker = (ObjectNode) payload.at("/workers/0");
        worker.putNull("station");
        assertValid(schema, payload);
        assertTrue(validator.validate(referenceMapper.treeToValue(payload, ReferenceDocument.class)).isValid());
        worker.remove("station");
        assertValid(schema, payload);
        assertTrue(validator.validate(referenceMapper.treeToValue(payload, ReferenceDocument.class)).isValid());

        station.putNull("supervisor");
        assertInvalid(schema, payload);
        station.remove("supervisor");
        assertInvalid(schema, payload);
    }

    @Test
    void portableValueConstraintsAgreeOnOrdinaryWireValues() throws IOException {
        var schema = schema(VMFJsonSchemaGenerator.newInstance(MODE), Document.class);
        var valid = (ObjectNode) mapper.readTree(VALID_DOCUMENT);
        valid.put("rate", 0.125).put("count", 2);
        assertValid(schema, valid);
        assertTrue(validator.validate(mapper.treeToValue(valid, Document.class)).isValid());

        var atExclusiveBound = valid.deepCopy().put("rate", 42.5);
        assertInvalid(schema, atExclusiveBound);
        assertFalse(validator.validate(mapper.treeToValue(atExclusiveBound, Document.class)).isValid());
        var belowRange = valid.deepCopy().put("count", 1);
        assertInvalid(schema, belowRange);
        assertFalse(validator.validate(mapper.treeToValue(belowRange, Document.class)).isValid());

        for (String name : List.of("A", "aa", "A1234")) {
            var invalidName = valid.deepCopy();
            ((ObjectNode) invalidName.at("/entries/0")).put("entry-name", name);
            assertInvalid(schema, invalidName);
            assertFalse(validator.validate(mapper.treeToValue(invalidName, Document.class)).isValid());
        }

        var javaOnly = valid.deepCopy().put("javaOnly", "123");
        assertValid(schema, javaOnly);
        assertFalse(validator.validate(mapper.treeToValue(javaOnly, Document.class)).isValid());
    }

    @Test
    void portableLengthCountsCodePointsAndEndAnchorRejectsFinalNewline() throws IOException {
        var schema = schema(VMFJsonSchemaGenerator.newInstance(MODE), Document.class);
        var valid = (ObjectNode) mapper.readTree(VALID_DOCUMENT);
        valid.put("glyph", "\uD83D\uDE80").put("anchored", "ABC");
        assertValid(schema, valid);
        assertTrue(validator.validate(mapper.treeToValue(valid, Document.class)).isValid());

        var finalNewline = valid.deepCopy().put("anchored", "ABC\n");
        assertInvalid(schema, finalNewline);
        assertFalse(validator.validate(mapper.treeToValue(finalNewline, Document.class)).isValid());
    }

    @Test
    void configuredConditionalAssertionIsConjoinedAtRootAndNestedType() throws IOException {
        var fields = new ArrayList<>(List.of("detail"));
        var generator = VMFJsonSchemaGenerator.newInstance(MODE).withTypeConstraint(Entry.class, Map.of(
                "if", Map.of("properties", Map.of("entry-name", Map.of("const", "A1")), "required", List.of("entry-name")),
                "then", Map.of("required", fields)));
        fields.set(0, "another-field");
        var rootSchema = schema(generator, Entry.class);
        assertInvalid(rootSchema, mapper.readTree("{\"entry-name\":\"A1\"}"));
        assertValid(rootSchema, mapper.readTree("{\"entry-name\":\"A1\",\"detail\":\"ok\"}"));
        assertInvalid(rootSchema, mapper.readTree("{\"detail\":\"ok\"}"));

        var nestedSchema = schema(generator, Document.class);
        var payload = (ObjectNode) mapper.readTree(VALID_DOCUMENT);
        assertInvalid(nestedSchema, payload);
        ((ObjectNode) payload.at("/entries/0")).put("detail", "ok");
        assertValid(nestedSchema, payload);
    }

    @Test
    void rawAssertionBesideConcreteReferenceIsEnforced() throws IOException {
        var schema = schema(VMFJsonSchemaGenerator.newInstance(MODE), Document.class);
        var payload = (ObjectNode) mapper.readTree(VALID_DOCUMENT);
        var detail = payload.putObject("primary").put("title", "One property");
        assertValid(schema, payload);
        detail.put("note", "This exceeds the maximum");
        assertInvalid(schema, payload);
    }

    private JsonSchema schema(VMFJsonSchemaGenerator generator, Class<? extends VObject> type) {
        JsonNode document = VMFJsonSchemaGenerator.createObjectMapper().valueToTree(generator.generateSchema(type));
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(document);
    }

    private static void assertValid(JsonSchema schema, JsonNode document) {
        var failures = schema.validate(document);
        assertTrue(failures.isEmpty(), failures::toString);
    }

    private static void assertInvalid(JsonSchema schema, JsonNode document) {
        assertFalse(schema.validate(document).isEmpty(), document::toString);
    }
}
