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
 */
package eu.mihosoft.vmf.jackson.test.defaults;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import eu.mihosoft.vmf.jackson.VMFJacksonModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NullableScalarDefaultsTest {
    @Test
    void writesExplicitClearsOnlyWhenMissingFieldsWouldRestoreScalarDefaults() throws IOException {
        for (boolean references : List.of(false, true)) {
            for (ObjectMapper mapper : List.of(new ObjectMapper(), new YAMLMapper(), new XmlMapper())) {
                mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
                        .withReferences(references));
                String format = mapper.getFactory().getFormatName();
                String missing = format.equals("XML") ? "<snapshot/>" : "{}";
                var model = mapper.readValue(missing, NullableScalarDefaults.class);
                assertEquals("draft", model.getLabel(), format);
                assertEquals(42, model.getCount(), format);
                assertEquals('X', model.getSymbol(), format);

                model.setLabel(null);
                model.setCount(null);
                model.setSymbol(null);
                String wire = mapper.writeValueAsString(model);
                var fields = mapper.readTree(wire);
                assertTrue(fields.path("display_label").isNull(), wire);
                assertTrue(fields.path("count").isNull(), wire);
                assertTrue(fields.path("symbol").isNull(), wire);
                assertFalse(fields.has("label"), wire);
                assertFalse(fields.has("unnamed"), wire);
                assertFalse(fields.has("explicitNull"), wire);
                assertFalse(fields.has("child"), wire);
                assertFalse(fields.has("related"), wire);

                var restored = mapper.readValue(wire, NullableScalarDefaults.class);
                assertNull(restored.getLabel(), wire);
                assertNull(restored.getCount(), wire);
                assertNull(restored.getSymbol(), wire);
            }
        }
    }
}
