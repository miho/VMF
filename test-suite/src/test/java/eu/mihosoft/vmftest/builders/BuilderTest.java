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
package eu.mihosoft.vmftest.builders;

import org.junit.Assert;
import org.junit.Test;

public class BuilderTest {
    @Test
    public void testWithNestedBuilders() {

        AClass.Builder b = AClass.newBuilder()
            .withName("my name")
            .withIds("id1", "id2", "id3")
            .withChildren(
                // lazy also for properties
                Child.newBuilder().withValue(1),
                Child.newBuilder().withValue(2),
                Child.newBuilder().withValue(3)
            )
            .withChild(
                // lazy also for properties
                Child2.newBuilder().withValue(4)
            );

        AClass anInstance = b.build();

        // junit assert values of anInstance are correct according to the builder

        Assert.assertEquals("my name", anInstance.getName());
        Assert.assertEquals(3, anInstance.getIds().size());
        Assert.assertEquals("id1", anInstance.getIds().get(0));
        Assert.assertEquals("id2", anInstance.getIds().get(1));
        Assert.assertEquals("id3", anInstance.getIds().get(2));
        Assert.assertEquals(3, anInstance.getChildren().size());
        Assert.assertEquals(1, anInstance.getChildren().get(0).getValue());
        Assert.assertEquals(2, anInstance.getChildren().get(1).getValue());
        Assert.assertEquals(3, anInstance.getChildren().get(2).getValue());

        Assert.assertEquals(4, anInstance.getChild().getValue());
    }

    @Test
    public void nestedBuildersMaterializePerSlotOnlyDuringBuild() {
        var child = Child.newBuilder().withValue(1);
        var single = Child2.newBuilder().withValue(2);
        var inputs = new java.util.ArrayList<>(java.util.Arrays.asList(child, null, child));
        var parent = AClass.newBuilder().withChildren(inputs).withChild(single);
        inputs.clear();

        var existing = Child.newInstance();
        var target = AClass.newBuilder().withChildren(existing).withChild(Child2.newInstance()).build();
        parent.applyTo(target);
        Assert.assertNull(target.getChild());
        Assert.assertEquals(1, target.getChildren().size());
        Assert.assertSame(existing, target.getChildren().get(0));
        parent.appendCollections(false).applyTo(target);
        Assert.assertTrue(target.getChildren().isEmpty());

        child.withValue(7);
        single.withValue(8);
        var first = parent.build();
        child.withValue(9);
        single.withValue(10);
        var second = parent.build();

        Assert.assertEquals(8, first.getChild().getValue());
        Assert.assertEquals(10, second.getChild().getValue());
        Assert.assertNotSame(first.getChild(), second.getChild());
        for (var built : new AClass[]{first, second}) {
            int expected = built == first ? 7 : 9;
            Assert.assertEquals(3, built.getChildren().size());
            Assert.assertEquals(expected, built.getChildren().get(0).getValue());
            Assert.assertNull(built.getChildren().get(1));
            Assert.assertEquals(expected, built.getChildren().get(2).getValue());
            Assert.assertNotSame(built.getChildren().get(0), built.getChildren().get(2));
        }
        Assert.assertNotSame(first.getChildren().get(0), second.getChildren().get(0));

        var direct = parent.withChildren(existing).withChild(first.getChild()).build();
        Assert.assertSame(existing, direct.getChildren().get(0));
        Assert.assertSame(first.getChild(), direct.getChild());
        Assert.assertNull(parent.withChild((Child2.Builder) null).build().getChild());
    }

    @Test
    public void testWithProperties() {

        AClass.Builder b = AClass.newBuilder()
            .withName("my name")
            .withIds("id1", "id2", "id3")
            .withChildren(
                // properties are instantiated immediately
                Child.newBuilder().withValue(1).build(),
                Child.newBuilder().withValue(2).build(),
                Child.newBuilder().withValue(3).build()
            )
            .withChild(
                // properties are instantiated immediately
                Child2.newBuilder().withValue(4).build()
            );

        AClass anInstance = b.build();

        // junit assert values of anInstance are correct according to the builder

        Assert.assertEquals("my name", anInstance.getName());
        Assert.assertEquals(3, anInstance.getIds().size());
        Assert.assertEquals("id1", anInstance.getIds().get(0));
        Assert.assertEquals("id2", anInstance.getIds().get(1));
        Assert.assertEquals("id3", anInstance.getIds().get(2));
        Assert.assertEquals(3, anInstance.getChildren().size());
        Assert.assertEquals(1, anInstance.getChildren().get(0).getValue());
        Assert.assertEquals(2, anInstance.getChildren().get(1).getValue());
        Assert.assertEquals(3, anInstance.getChildren().get(2).getValue());


        Assert.assertEquals(4, anInstance.getChild().getValue());
    }

    @Test
    public void applyFromAndApplyToKeepOrdinaryReferenceIdentityAndNullPositions() {
        Child leaf = Child.newInstance();
        Child2 single = Child2.newInstance();
        AClass source = AClass.newBuilder().withName("source")
                .withChildren(new Child[]{leaf, null, leaf}).withChild(single).build();
        AClass.Builder builder = AClass.newBuilder().appendCollections(false).applyFrom(source);
        source.getChildren().clear();
        source.setChild(null);

        AClass target = AClass.newBuilder().withChildren(Child.newInstance()).build();
        var list = target.getChildren();
        builder.applyTo(target);
        Assert.assertSame(list, target.getChildren());
        Assert.assertEquals("source", target.getName());
        Assert.assertEquals(3, list.size());
        Assert.assertSame(leaf, list.get(0));
        Assert.assertNull(list.get(1));
        Assert.assertSame(leaf, list.get(2));
        Assert.assertSame(single, target.getChild());

        builder.appendCollections(true).applyTo(target);
        Assert.assertEquals(6, list.size());
        Assert.assertSame(leaf, list.get(3));
        Assert.assertNull(list.get(4));
        Assert.assertSame(leaf, list.get(5));
        Assert.assertTrue(source.getChildren().isEmpty());
    }

    @Test
    public void applyFromReplacesLazyReferenceStaging() {
        var child = Child2.newBuilder().withValue(1).build();
        var leaf = Child.newBuilder().withValue(2).build();
        var source = AClass.newBuilder().withChild(child).withChildren(new Child[]{leaf, null, leaf}).build();

        for (boolean append : new boolean[]{false, true}) {
            var staged = AClass.newBuilder().appendCollections(append)
                    .withChild(Child2.newBuilder().withValue(11))
                    .withChildren(Child.newBuilder().withValue(12))
                    .applyFrom(source);
            var built = staged.build();
            var target = AClass.newInstance();
            staged.applyTo(target);
            for (var imported : new AClass[]{built, target}) {
                Assert.assertSame(child, imported.getChild());
                Assert.assertEquals(3, imported.getChildren().size());
                Assert.assertSame(leaf, imported.getChildren().get(0));
                Assert.assertNull(imported.getChildren().get(1));
                Assert.assertSame(leaf, imported.getChildren().get(2));
            }

            var laterChild = Child2.newBuilder().withValue(11);
            staged.withChild(laterChild).withChildren(Child.newBuilder().withValue(12));
            laterChild.withValue(13);
            var rebuilt = staged.build();
            Assert.assertEquals(13, rebuilt.getChild().getValue());
            Assert.assertEquals(1, rebuilt.getChildren().size());
            Assert.assertEquals(12, rebuilt.getChildren().get(0).getValue());

            var cleared = staged.applyFrom(AClass.newInstance()).build();
            Assert.assertNull(cleared.getChild());
            Assert.assertTrue(cleared.getChildren().isEmpty());
        }
    }

    @Test
    public void applyToUsesTheInheritedLiteralDefaultUntilAnAncestorIsImported() {
        InheritedDefaultChild target = InheritedDefaultChild.newBuilder().withValue(99).build();
        var stage = InheritedDefaultChild.newBuilder();
        stage.applyTo(target);
        Assert.assertEquals(123, target.getValue());

        DefaultChild source = DefaultChild.newBuilder().withValue(57).build();
        stage.applyFrom(source).applyTo(target);
        Assert.assertEquals(57, target.getValue());
        Assert.assertEquals(57, stage.build().getValue());
    }
}
