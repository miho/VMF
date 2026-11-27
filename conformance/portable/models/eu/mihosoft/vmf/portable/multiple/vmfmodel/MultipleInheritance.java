package eu.mihosoft.vmf.portable.multiple.vmfmodel;

import eu.mihosoft.vmf.core.InterfaceOnly;

// From test-suite vmftest/propertyinheritance (2022): a type with several model supertypes.
@InterfaceOnly
interface WithX {
    int getX();
}

@InterfaceOnly
interface WithY {
    int getY();
}

interface Location {
    String getLabel();
}

interface LocationXY extends Location, WithX, WithY {
}
