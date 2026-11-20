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
package eu.mihosoft.vmftest.cross_ref.vmfmodel;

import eu.mihosoft.vmf.core.Refers;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;

interface CrossRefGraph {
    @Contains EntityOneA[] getLefts();
    @Contains EntityTwoA[] getRights();
}

interface EntityOneA {
    @Refers(opposite="ref")
    EntityTwoA getRef();
}
interface EntityTwoA {
    @Refers(opposite="ref")
    EntityOneA getRef();
}
interface SpecializedEntityOneA extends EntityOneA {
    String getLabel();
}


interface OneToManyGraph {
    @Contains EntityOneB[] getMembers();
    @Contains EntityTwoB[] getOwners();
}

interface EntityOneB {
    String getId();

    @Refers(opposite="refs")
    EntityTwoB getRef();
}
interface EntityTwoB {
    String getId();

    @Refers(opposite="ref")
    EntityOneB[] getRefs();
}

interface EntityOneC {
    @Refers(opposite="refs")
    EntityTwoC[] getRefs();
}
interface EntityTwoC {
    @Refers(opposite="refs")
    EntityOneC[] getRefs();
}

// one-to-one reference that is its own opposite (#97)
interface Partner {
    String getName();

    @Refers(opposite="partner")
    Partner getPartner();
}

// one-to-one reference declared on interface-only types (#97)
@InterfaceOnly
interface Selector {
    @Refers(opposite="selectedBy")
    Selectable getSelected();
}
@InterfaceOnly
interface Selectable {
    @Refers(opposite="selected")
    Selector getSelectedBy();
}
interface Panel extends Selector {
    String getName();
}
interface Button extends Selectable {
    String getName();
}
