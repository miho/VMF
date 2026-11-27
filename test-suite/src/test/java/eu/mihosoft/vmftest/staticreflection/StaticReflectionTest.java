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
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.diff.ModelDiff;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class StaticReflectionTest {
    @Test
    public void crossConcreteDiffPreservesSourceOnlyPropertiesAndImportedAliases() {
        var source = TypeC.newBuilder().withName("retained").withPropA1(17).withPropB1(2.5).build();
        var incoming = TypeC.newBuilder().withName("imported").withPropA2("reference").build();
        incoming.setProp(incoming);
        var desired = TypeB.newBuilder().withPropB1(25).withProp(incoming).withPropB2(incoming).build();

        var changes = ModelDiff.diff(source, desired.asReadOnly());
        Assert.assertEquals(Set.of("prop", "propB1", "propB2"),
                changes.stream().map(Change::propertyName).collect(Collectors.toSet()));
        Assert.assertNull(source.getProp());
        Assert.assertThrows(IllegalArgumentException.class, () -> ModelDiff.apply(source.clone(), changes));
        ModelDiff.apply(source, changes);
        Assert.assertEquals("retained", source.getName());
        Assert.assertEquals(17, source.getPropA1());
        Assert.assertEquals(25, source.getPropB1(), 0.0);
        var copied = source.getProp();
        Assert.assertNotSame(incoming, copied);
        Assert.assertSame(copied, source.getPropB2());
        Assert.assertSame(copied, copied.getProp());
        Assert.assertEquals("imported", copied.getName());
        Assert.assertEquals("reference", copied.getPropA2());

        desired.setPropB2(null);
        var clearing = ModelDiff.diff(source, desired);
        Assert.assertEquals(List.of("propB2"), clearing.stream().map(Change::propertyName).collect(Collectors.toList()));
        ModelDiff.apply(source, clearing);
        Assert.assertNull(source.getPropB2());
        Assert.assertSame(copied, source.getProp());
        clearing.get(0).undo();
        Assert.assertSame(copied, source.getPropB2());
        for (int i = changes.size() - 1; i >= 0; i--) changes.get(i).undo();
        Assert.assertEquals(2.5, source.getPropB1(), 0.0);
        Assert.assertNull(source.getProp());
        Assert.assertNull(source.getPropB2());
        Assert.assertSame(incoming, incoming.getProp());
    }

    @Test
    public void crossConcreteDiffUpdatesTheExistingDetachedSingularTarget() {
        var existing = TypeA.newBuilder().withPropA2("before").build();
        var incoming = TypeA.newBuilder().withPropA2("after").build();
        var source = TypeC.newBuilder().withPropB2(existing).build();
        var desired = TypeB.newBuilder().withPropB2(incoming).build();
        var changes = ModelDiff.diff(source, desired);
        Assert.assertEquals(1, changes.size());
        Assert.assertSame(existing, changes.get(0).object());
        Assert.assertEquals("propA2", changes.get(0).propertyName());
        ModelDiff.apply(source, changes);
        Assert.assertSame(existing, source.getPropB2());
        Assert.assertEquals("after", existing.getPropA2());
        changes.get(0).undo();
        Assert.assertEquals("before", existing.getPropA2());
    }

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
