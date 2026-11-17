/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
package eu.mihosoft.vmf.jackson.test.references02;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import eu.mihosoft.vmf.jackson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ReferenceSchemaValidationTest {
    private final ObjectMapper mapper=new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true));
    private JsonNode schema() {
        return VMFJsonSchemaGenerator.createObjectMapper().valueToTree(VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withReferences(true).generateSchema(Project.class));
    }
    @Test void nativeReferenceGraphMatchesGeneratedDraft07() throws Exception {
        Project project=ReferencesTest.project();JsonNode payload=mapper.readTree(mapper.writeValueAsString(project));
        var errors=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema()).validate(payload);
        assertTrue(errors.isEmpty(),errors.toString());
        assertEquals(ReferencesTest.signature(project),ReferencesTest.signature(mapper.treeToValue(payload,Project.class)));
    }
    @Test void referenceFieldsHaveIdTypesAndImmutableValuesStayByValue() {
        JsonNode schema=schema();String task=Task.class.getName();
        assertEquals("string",schema.at("/properties/focus/type/0").asText());
        assertEquals("array",schema.at("/properties/history/type").asText());
        assertEquals("integer",schema.at("/properties/history/items/type/1").asText());
        assertEquals("string",schema.at("/definitions/"+task+"/properties/dependsOn/type/0").asText());
        assertFalse(schema.at("/definitions/"+task+"/properties/section").isObject());
        assertEquals("#/definitions/"+Version.class.getName(),schema.at("/properties/version/$ref").asText());
        assertEquals("integer",schema.at("/properties/@vmf-id/type/1").asText());
    }
    @Test void wrongReferenceRepresentationsAreRejected() throws Exception {
        JsonNode payload=mapper.readTree(mapper.writeValueAsString(ReferencesTest.project()));
        var validator=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema());
        ((ObjectNode)payload).putObject("focus").put("name","nested object instead of id");
        assertFalse(validator.validate(payload).isEmpty());
        ((ObjectNode)payload).put("focus",true);assertFalse(validator.validate(payload).isEmpty());
    }
    @Test void defaultSchemaRemainsContainmentOnlyAndModeCanBeDisabled() {
        var generator=VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL);
        assertFalse(generator.isReferencesEnabled());assertTrue(generator.withReferences(true).isReferencesEnabled());
        JsonNode schema=VMFJsonSchemaGenerator.createObjectMapper().valueToTree(generator.withReferences(false).generateSchema(Project.class));
        assertFalse(schema.path("properties").has("focus"));assertFalse(schema.path("properties").has("@vmf-id"));
    }
}
