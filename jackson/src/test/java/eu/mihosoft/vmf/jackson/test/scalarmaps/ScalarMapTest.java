/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmf.jackson.test.scalarmaps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import eu.mihosoft.vmf.jackson.VMFJsonSchemaGenerator;
import eu.mihosoft.vmf.runtime.core.Change;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScalarMapTest {
    @Test
    void jsonAndSchemaPreserveDeclaredValues() throws Exception {
        var mode = VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL;
        ObjectMapper mapper = new ObjectMapper().registerModule(VMFJacksonModule.newInstance(mode));
        String json = "{\"values\":{\"whole number\":2,\"\":null},\"codes\":{\"k\":\"x\"},\"bytes\":{\"k\":127}}";
        var model = mapper.readValue(json, Readings.class);
        assertEquals(Double.valueOf(2), model.getValues().get("whole number"));
        assertTrue(model.getValues().containsKey(""));
        assertNull(model.getValues().get(""));
        assertEquals(Character.valueOf('x'), model.getCodes().get("k"));
        assertEquals(Byte.valueOf((byte) 127), model.getBytes().get("k"));

        model.getValues().put("non-finite", Double.NEGATIVE_INFINITY);
        JsonNode payload = mapper.readTree(mapper.writeValueAsBytes(model));
        assertEquals(model.getValues(), mapper.treeToValue(payload, Readings.class).getValues());
        JsonNode schema = VMFJsonSchemaGenerator.createObjectMapper().valueToTree(
                VMFJsonSchemaGenerator.newInstance(mode).withReferences(true).generateSchema(Readings.class));
        var validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema);
        assertTrue(validator.validate(payload).isEmpty());
        ((ObjectNode)payload.get("values")).put("bad", true);
        assertFalse(validator.validate(payload).isEmpty());
    }

    @Test
    void mapEditsRetainOrderWhenObserversEditTheSameKey() {
        var model = Readings.newInstance();
        var changes = model.vmf().changes();
        changes.start();
        List<Change> local = new ArrayList<>();
        var subscription = changes.addListener(c -> {
            local.add(c);
            if (local.size() == 1) model.getCodes().put("k", 'b');
        }, false);
        model.getCodes().put("k", 'a');
        subscription.unsubscribe();
        changes.stop();
        assertEquals(2, local.size());
        assertEquals(2, changes.all().size());
        var first = changes.all().get(0);
        var second = changes.all().get(1);
        assertEquals(Change.ChangeType.MAP, first.getType());
        assertEquals(Map.of("k", 'a'), first.mapChange().orElseThrow().added().entries());
        assertFalse(first.isUndoable());
        second.undo();
        assertTrue(first.isUndoable());
        first.undo();
        assertTrue(model.getCodes().isEmpty());
        var copy = Readings.newInstance();
        first.apply(copy);
        second.apply(copy);
        assertEquals(Map.of("k", 'b'), copy.getCodes());
    }
}
