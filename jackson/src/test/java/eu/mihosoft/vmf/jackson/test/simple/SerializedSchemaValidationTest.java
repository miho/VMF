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
package eu.mihosoft.vmf.jackson.test.simple;

import com.fasterxml.jackson.databind.*;
import com.networknt.schema.*;
import eu.mihosoft.vmf.jackson.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class SerializedSchemaValidationTest {
    @Test void concreteBaseAndSubtypeInstancesValidateAgainstGeneratedDraft07() throws Exception {
        Map<String,String> aliases=Map.of("person",Person.class.getName(),"employee",Employee.class.getName());
        ObjectMapper mapper=new ObjectMapper().registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withTypeAliases(aliases));
        MyModel model=MyModel.newInstance();model.getPersons().add(Person.newBuilder().withName("base").withAge(20).build());
        model.getPersons().add(Employee.newBuilder().withName("subtype").withAge(30).withEmployeeId("1234").build());
        JsonNode payload=mapper.readTree(mapper.writeValueAsString(model));
        JsonNode schema=VMFJsonSchemaGenerator.createObjectMapper().valueToTree(VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL).withTypeAliases(aliases).generateSchema(MyModel.class));
        var errors=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema).validate(payload);
        assertTrue(errors.isEmpty(),errors.toString());
        assertEquals("person",payload.at("/persons/0/@vmf-type").asText());assertEquals("employee",payload.at("/persons/1/@vmf-type").asText());
        MyModel copy=mapper.treeToValue(payload,MyModel.class);assertTrue(model.vmf().content().equals(copy));
    }
}
