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
}
