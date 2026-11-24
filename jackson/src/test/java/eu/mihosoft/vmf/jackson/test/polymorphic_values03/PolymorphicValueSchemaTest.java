/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.jackson.test.polymorphic_values03;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PolymorphicValueSchemaTest {
    private static final Map<String, String> ALIASES = Map.of(
            "document", Document.class.getName(),
            "fixed", FixedValue.class.getName(),
            "mutable", MutableValue.class.getName());

    private ObjectMapper mapper() {
        return new ObjectMapper().registerModule(
                VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withReferences(true).withTypeAliases(ALIASES));
    }

    private JsonNode schema() {
        return VMFJsonSchemaGenerator.createObjectMapper().valueToTree(
                VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withReferences(true).withTypeAliases(ALIASES).generateSchema(Document.class));
    }

    private com.networknt.schema.JsonSchema validator() {
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema());
    }

    private Document mixedDocument() {
        MutableValue mutable = MutableValue.newBuilder().withName("owned mutable value").build();
        FixedValue fixed = FixedValue.newBuilder().withName("immutable value").build();
        Document document = Document.newInstance();
        document.getOwnedValues().add(mutable);
        document.setSelected(fixed);
        document.getValues().addAll(List.of(fixed, mutable));
        document.setMutableOnly(mutable);
        return document;
    }

    @Test
    void scalarAndMixedListImmutableImplementationsValidateAsObjectsAndMutableValuesAsIds() throws Exception {
        ObjectMapper mapper = mapper();
        Document original = mixedDocument();
        JsonNode payload = mapper.readTree(mapper.writeValueAsString(original));
        var errors = validator().validate(payload);
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals("fixed", payload.at("/selected/@vmf-type").asText());
        assertEquals(payload.at("/selected/@vmf-id").asText(), payload.at("/values/0").textValue());
        assertTrue(payload.at("/values/1").isTextual());
        assertTrue(payload.path("mutableOnly").isTextual());

        Document copy = mapper.treeToValue(payload, Document.class);
        assertInstanceOf(FixedValue.class, copy.getSelected());
        assertEquals("immutable value", copy.getSelected().getName());
        assertSame(copy.getSelected(), copy.getValues().get(0));
        assertSame(copy.getOwnedValues().get(0), copy.getValues().get(1));
        assertSame(copy.getOwnedValues().get(0), copy.getMutableOnly());
        assertNotSame(original.getOwnedValues().get(0), copy.getOwnedValues().get(0));
    }

    @Test
    void immutableOnlyListStillUsesPolymorphicObjectsWithoutRequiringAnId() throws Exception {
        Document document = Document.newInstance();
        FixedValue fixed = FixedValue.newBuilder().withName("immutable only").build();
        document.setSelected(fixed);
        document.getValues().add(FixedValue.newBuilder().withName("separate value").build());
        ObjectMapper mapper = mapper();
        JsonNode payload = mapper.readTree(mapper.writeValueAsString(document));
        var errors = validator().validate(payload);
        assertTrue(errors.isEmpty(), errors.toString());
        Document copy = mapper.treeToValue(payload, Document.class);
        assertInstanceOf(FixedValue.class, copy.getSelected());
        assertInstanceOf(FixedValue.class, copy.getValues().get(0));
        assertFalse(payload.path("selected").has("@vmf-id"));
    }

    @Test
    void immutableOptionsDoNotPermitArbitraryObjectsOrWeakenMutableOnlyReferences() throws Exception {
        ObjectMapper mapper = mapper();
        JsonNode payload = mapper.readTree(mapper.writeValueAsString(mixedDocument()));
        var validator = validator();
        assertFalse(validator.validate(((ObjectNode) payload.deepCopy())
                .set("selected", mapper.createObjectNode().put("name", "missing discriminator"))).isEmpty());
        assertFalse(validator.validate(((ObjectNode) payload.deepCopy())
                .set("selected", mapper.createObjectNode().put("@vmf-type", "mutable").put("name", "nested mutable"))).isEmpty());
        assertFalse(validator.validate(((ObjectNode) payload.deepCopy())
                .set("mutableOnly", payload.path("selected").deepCopy())).isEmpty());
        JsonNode mutableSchema = schema().at("/properties/mutableOnly");
        assertEquals("string", mutableSchema.at("/type/0").asText());
        assertEquals("integer", mutableSchema.at("/type/1").asText());
        assertEquals("null", mutableSchema.at("/type/2").asText());
        assertFalse(mutableSchema.has("oneOf"));
    }
}
