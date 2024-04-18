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
package eu.mihosoft.vmftest.propertyorder;

import org.junit.Assert;
import org.junit.Test;

public class PropertyOrderTest {

    @Test
    public void propertyDefaultOrderTest() {

        // Uses default order (no custom property order specified)
        // -> currently, default order is alphabetically
        DefaultOrder defaultOrder = DefaultOrder.newInstance();

        // this is the order in which properties are supposed to be visited
        String[] propertiesD = new String[] {"b", "d", "x", "z"};

        // assert that the properties are visited in the correct order
        for(int i = 0; i < defaultOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesD[i], defaultOrder.vmf().reflect().properties().get(i).getName());
        }

    }

    @Test
    public void propertyCustomOrderTest() {

        // Uses custom order (defines custom property order)
        CustomOrder customOrder = CustomOrder.newInstance();

        // this is the order in which properties are supposed to be visited
        String[] propertiesC = new String[] {"z", "b", "d", "x"};

        // assert that the properties are visited in the correct order
        for(int i = 0; i < customOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesC[i], customOrder.vmf().reflect().properties().get(i).getName());
        }

    }

    @Test
    public void inheritedPropertyOrderTestWithoutBaseOrder() {

        // In this test we ensure that inherited properties work correctly.
        // It is valid for them to not define a custom order even if the properties of this
        // class do have a specified order.
        //
        // All inherited properties are visited prior to the properties of this class.
        InheritedOrderSubClassWithoutBaseOrder customOrder = InheritedOrderSubClassWithoutBaseOrder.newInstance();

        // Expected property order
        String[] propertiesC = new String[] {"baseA", "baseB", "baseZ", "a", "z", "b"};

        // assert that the properties are visited in the correct order
        for(int i = 0; i < customOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesC[i], customOrder.vmf().reflect().properties().get(i).getName());
        }

    }

    @Test
    public void inheritedPropertyOrderTestWithBaseOrder() {

        // In this test we ensure that inherited properties work correctly.
        // It is valid for them to define a custom order which has to be used
        // by the iterators of this class.
        //
        // All inherited properties are visited prior to the properties of this class.
        InheritedOrderSubClassWithBaseOrder customOrder = InheritedOrderSubClassWithBaseOrder.newInstance();

        // Expected property order
        String[] propertiesC = new String[] {"baseA", "baseZ", "baseB", "a", "z", "b"};

        // assert that the propperties are visited in the correct order
        for(int i = 0; i < customOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesC[i], customOrder.vmf().reflect().properties().get(i).getName());
        }

    }

    @Test
    public void inheritedPropertyOrderTestWithRedefinedBaseOrder() {

        // In this test we ensure that inherited properties work correctly.
        // It is valid for them to define a custom order which has to be used
        // by the iterators of this class.
        // In addition to the previous test we also ensure that the order of inherited
        // properties can be redeclared in the class that inherits them
        //
        //
        // All inherited properties are visited prior to the properties of this class.
        InheritedOrderSubClassWithRedefinedBaseOrder customOrder = InheritedOrderSubClassWithRedefinedBaseOrder.newInstance();

        // Expected property order
        String[] propertiesC = new String[] {"baseA", "baseZ", "baseB", "z", "b", "a"};

        // assert that the propperties are visited in the correct order
        for(int i = 0; i < customOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesC[i], customOrder.vmf().reflect().properties().get(i).getName());
        }

    }

}
