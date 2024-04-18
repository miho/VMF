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
package eu.mihosoft.vmftest.propertyinheritance.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface WithX {
    @GetterOnly
    double getX();
}

@InterfaceOnly
interface WithY {
    @GetterOnly
    double getY();
}

@InterfaceOnly
interface WithXY extends WithX, WithY {

}


interface Location {

}


interface LocationX extends Location, WithX {

}

interface LocationY extends Location, WithY {

}

interface LocationXY extends WithXY, LocationX, LocationY {

}

@InterfaceOnly
interface WithLocation {
    @GetterOnly
    Location getLocation();
}

@InterfaceOnly
interface WithLocationX extends WithLocation {
    @GetterOnly
    LocationX getLocation();
}

@InterfaceOnly
interface WithLocationY extends WithLocation {
    @GetterOnly
    LocationY getLocation();
}

@InterfaceOnly
interface WithLocationXY extends WithLocationX, WithLocationY {
    @GetterOnly
    LocationXY getLocation();
}


interface PropSample01 {
    
    @Contains
    GCode1 getGCode1();

    @Contains
    GCode2 getGCode2();

    @Contains
    GCode3 getGCode3();

    @Contains
    GCode4 getGCode4();

}

interface GCode1 extends WithLocationXY {
    LocationXY getLocation();
}

interface GCode2 extends WithLocationX {
    LocationX getLocation();
}

interface GCode3 extends WithLocationY {
    LocationY getLocation();
}

interface GCode4 extends WithLocation {
    Location getLocation();
}
