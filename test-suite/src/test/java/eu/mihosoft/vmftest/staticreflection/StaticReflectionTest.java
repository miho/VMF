/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmftest.staticreflection;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Type;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class StaticReflectionTest {
    @Test
    public void staticReflectionTest() {
        int propSize = Root.type().reflect().properties().size();

        Assert.assertEquals(1, propSize);

        Property p = Root.type().reflect().properties().get(0);

        try {
            p.set(null);
            Assert.fail(); // shouldn't be reached
        } catch(RuntimeException ex) {
            // exception expected
            ex.printStackTrace();
        }

        List<Type> superTypes = p.getType().superTypes();

        Assert.assertEquals(2, superTypes.size());

        List<String> typeNames = p.getType().superTypes().stream().map(t->t.getName()).collect(Collectors.toList());

        Assert.assertEquals(Arrays.asList(
                "eu.mihosoft.vmftest.staticreflection.TypeA", "eu.mihosoft.vmftest.staticreflection.TypeB"),
                typeNames
        );

        propSize = TypeA.type().reflect().properties().size();
        Assert.assertEquals(3,propSize);
        propSize = TypeB.type().reflect().properties().size();
        Assert.assertEquals(3,propSize);
        propSize = TypeC.type().reflect().properties().size();
        Assert.assertEquals(6,propSize);

    }
}
