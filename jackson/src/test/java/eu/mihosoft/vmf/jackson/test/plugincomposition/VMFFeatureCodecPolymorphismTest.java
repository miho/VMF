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
package eu.mihosoft.vmf.jackson.test.plugincomposition;

import eu.mihosoft.vmf.jackson.VMFFeatureCodec;
import eu.mihosoft.vmf.jackson.test.plugincomposition.device.Device;
import eu.mihosoft.vmf.jackson.test.simple.Employee;
import eu.mihosoft.vmf.jackson.test.simple.MyModel;
import eu.mihosoft.vmf.runtime.core.FeatureAttachments;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

class VMFFeatureCodecPolymorphismTest {
    @TestFactory
    Stream<DynamicTest> restoresSubtypeInAttachedModel(@TempDir Path directory) {
        return Stream.of("json", "yaml", "yml", "xml").map(extension -> dynamicTest(extension,
                () -> roundTripSubtype(directory.resolve("device." + extension))));
    }

    private void roundTripSubtype(Path file) throws IOException {
        ModelRegistry registry = new ModelRegistry(List.of(
                Device.type().reflect().allTypes(), MyModel.type().reflect().allTypes()));
        Device device = Device.newInstance();
        Employee operator = Employee.newBuilder().withName("007").withAge(42).withEmployeeId("E<&17").build();
        MyModel operators = MyModel.newBuilder().withPersons(operator).build();
        FeatureAttachments features = new FeatureAttachments(registry);
        features.attach(device, operators);

        VMFFeatureCodec codec = new VMFFeatureCodec(registry);
        codec.save(file, device, features);

        var loaded = codec.load(file, Device.class);
        MyModel restored = loaded.attachments().find(loaded.owner(), MyModel.class).orElseThrow();
        assertEquals(1, restored.getPersons().size());
        Employee restoredOperator = assertInstanceOf(Employee.class, restored.getPersons().get(0));
        assertEquals("007", restoredOperator.getName());
        assertEquals(42, restoredOperator.getAge());
        assertEquals("E<&17", restoredOperator.getEmployeeId());
        assertNull(restoredOperator.getAddress());
        assertSame(restored, restoredOperator.getModel());
    }

    @Test
    void restoresEmptyModeledListFromXml(@TempDir Path directory) throws IOException {
        ModelRegistry registry = new ModelRegistry(List.of(
                Device.type().reflect().allTypes(), MyModel.type().reflect().allTypes()));
        Device device = Device.newInstance();
        FeatureAttachments features = new FeatureAttachments(registry);
        features.attach(device, MyModel.newInstance());

        VMFFeatureCodec codec = new VMFFeatureCodec(registry);
        Path file = directory.resolve("device.xml");
        codec.save(file, device, features);

        var loaded = codec.load(file, Device.class);
        MyModel restored = loaded.attachments().find(loaded.owner(), MyModel.class).orElseThrow();
        assertTrue(restored.getPersons().isEmpty());
    }
}
