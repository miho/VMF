package eu.mihosoft.vmf.conformance.multiplebases.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;

interface Trunk {
    @DefaultValue("\"shared\"")
    String getTitle();
}

interface PrimaryRecord extends Trunk {
    @DefaultValue("5")
    int getLevel();
}

interface SecondaryRecord extends Trunk {
    @DefaultValue("11")
    int getWeight();

    PrimaryRecord getPeer();
}

interface JoinedRecord extends PrimaryRecord, SecondaryRecord {
    String getId();
}

interface RecordEnvelope {
    @Contains
    SecondaryRecord getRecord();
}

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
interface WithXY extends WithX, WithY { }

interface Coordinate { }

interface CoordinateX extends Coordinate, WithX { }

interface CoordinateY extends Coordinate, WithY { }

interface CoordinateXY extends WithXY, CoordinateX, CoordinateY { }
