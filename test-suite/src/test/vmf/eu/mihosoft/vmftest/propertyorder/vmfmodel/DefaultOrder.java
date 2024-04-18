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
package eu.mihosoft.vmftest.propertyorder.vmfmodel;

import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.PropertyOrder;

interface DefaultOrder {
    String getZ();

    Element getB();

    Integer getD();

    double getX();
}

interface CustomOrder {

    @PropertyOrder(index = 3)
    Integer getD();

    @PropertyOrder(index = 1)
    String getZ();

    @PropertyOrder(index = 2)
    Element getB();

    @PropertyOrder(index =4)
    double getX();
}

interface Element {

}

interface InheritedBaseWithoutCustomOrder {
    String getBaseA();
    String getBaseZ();
    String getBaseB();
}

interface InheritedOrderSubClassWithoutBaseOrder extends InheritedBaseWithoutCustomOrder {

    @PropertyOrder(index = 0)
    String getA();
    @PropertyOrder(index = 1)
    String getZ();
    @PropertyOrder(index = 2)
    String getB();
}

interface InheritedBaseWithCustomOrder {
    @PropertyOrder(index = 0)
    String getBaseA();
    @PropertyOrder(index = 1)
    String getBaseZ();
    @PropertyOrder(index = 2)
    String getBaseB();
}

interface InheritedOrderSubClassWithBaseOrder extends InheritedBaseWithCustomOrder {

    @PropertyOrder(index = 0)
    String getA();
    @PropertyOrder(index = 1)
    String getZ();
    @PropertyOrder(index = 2)
    String getB();
}

interface InheritedOrderSubClassWithRedefinedBaseOrder extends InheritedOrderSubClassWithBaseOrder {
    @PropertyOrder(index = 0)
    String getZ();

    @PropertyOrder(index = 1)
    String getB();

    @PropertyOrder(index = 2)
    String getA();
}

// redeclare property order unchanged 1
// -> this is a compile-only test
interface InheritedOrderSubClassWithRedefinedBaseOrderUnchanged extends InheritedOrderSubClassWithBaseOrder {
    @PropertyOrder(index = 0)
    String getA();
    @PropertyOrder(index = 1)
    String getZ();
    @PropertyOrder(index = 2)
    String getB();
}

// redeclare property order unchanged 2
// -> this is a compile-only test
@InterfaceOnly
interface BaseClass {
    @PropertyOrder(index=0)
    @GetterOnly
    Object getValue();
}
interface Inherited extends BaseClass {

    // this should be allowed
    // -> until v0.2.6.1 it wasn't
    //    because of the property type change
    //    which is 'Object' in the getter-only
    //    and 'Integer' here. that's where
    //    VMF checks for redeclared property
    //    order failed
    @PropertyOrder(index=0)
    Integer getValue();

}