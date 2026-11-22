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
package eu.mihosoft.vmftest.staticreflection;

import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmftest.tostring.Child;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class ModelRegistryTest {
    @Test
    public void composesIndependentlyGeneratedModels() {
        Root root = Root.newInstance();
        Child child = Child.newInstance();
        List<Type> coreTypes = root.vmf().reflect().allTypes();
        List<Type> pluginTypes = child.vmf().reflect().allTypes();

        ModelRegistry coreOnly = new ModelRegistry(List.of(coreTypes));
        assertFalse(coreOnly.typeOf(child).isPresent());

        ModelRegistry composed = new ModelRegistry(List.of(coreTypes, pluginTypes));
        List<Type> expected = new ArrayList<>(coreTypes);
        expected.addAll(pluginTypes);
        assertEquals(expected, composed.allTypes());
        assertSame(Child.type(), composed.findType(Child.class.getName()).orElseThrow());
        assertSame(Child.type(), composed.typeOf(child.asReadOnly()).orElseThrow());
        assertSame(Root.type(), composed.typeOf(root).orElseThrow());
        assertEquals(List.of(TypeB.type(), TypeC.type()), composed.typesAssignableTo(TypeB.class));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void rejectsConflictingTypeIdentityAndDeduplicatesRepeatedRegistration() {
        List<Type> types = Root.newInstance().vmf().reflect().allTypes();
        ModelRegistry repeated = new ModelRegistry(List.of(types, types));
        assertEquals(types, repeated.allTypes());

        Type conflicting = Type.newInstance(true, false, false, Root.type().getName(), Child.class);
        assertThrows(IllegalArgumentException.class, () -> new ModelRegistry(List.of(types, List.of(conflicting))));
    }
}
