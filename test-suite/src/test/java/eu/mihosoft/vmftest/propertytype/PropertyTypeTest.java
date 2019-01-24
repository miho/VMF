/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmftest.propertytype;

import eu.mihosoft.vmf.runtime.core.Property;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

public class PropertyTypeTest {

    @Test
    public void testPropertyTypes() {
        EntityWithProperties e = EntityWithProperties.newInstance();
        ChildEntity cE = ChildEntity.newInstance();

        Optional<Property> ids = e.vmf().reflect().propertyByName("ids");
        Optional<Property> children = e.vmf().reflect().propertyByName("children");
        Optional<Property> entity = e.vmf().reflect().propertyByName("entity");

        Optional<Property> name = cE.vmf().reflect().propertyByName("name");

        Assert.assertTrue("Property 'ids' must exist", ids.isPresent());
        Assert.assertTrue("Property 'children' must exist", children.isPresent());
        Assert.assertTrue("Property 'entity' must exist", entity.isPresent());
        Assert.assertTrue("Property 'name' must exist", name.isPresent());

        Assert.assertTrue(
                "Property 'ids' is a list type but is not flagged as such",
                ids.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'ids' is no model type but it is flagged as such",
                !ids.get().getType().isModelType());
        Assert.assertTrue(
                "Property 'children' is a list type but is not flagged as such",
                children.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'children' is a model type but it is not flagged as such",
                children.get().getType().isModelType());
        Assert.assertTrue(
                "Property 'entity' is no list type but is flagged as such",
                !entity.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'entity' is a model type but it is not flagged as such",
                entity.get().getType().isModelType());

        Assert.assertEquals(
                "eu.mihosoft.vmftest.propertytype.ChildEntity",
                entity.get().getType().getName());

        Assert.assertTrue(
                "Property 'name' is no list type but is flagged as such",
                !name.get().getType().isListType());
        Assert.assertTrue(
                "Type of property 'name' is no model type but it is flagged as such",
                !name.get().getType().isModelType());
    }
}
