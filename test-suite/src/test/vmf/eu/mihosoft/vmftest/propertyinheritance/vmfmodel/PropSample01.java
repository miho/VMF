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
