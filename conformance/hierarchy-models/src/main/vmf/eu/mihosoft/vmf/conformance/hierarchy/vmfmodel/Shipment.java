package eu.mihosoft.vmf.conformance.hierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

interface Parcel { String getId(); }

interface TrackedParcel extends Parcel { String getTrackingCode(); }

interface Inspection { String getId(); }

interface ParcelInspection extends Inspection { boolean isCleared(); }

@InterfaceOnly
interface ParcelDeck<T extends Parcel> {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    T[] getParcels();
}

@InterfaceOnly
interface ParcelSelection<T extends Parcel> {
    T getSelected();
    T[] getHistory();
}

@InterfaceOnly
interface InspectionDeck<U extends Inspection> {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    U[] getInspections();

    U getLatestInspection();
}

@InterfaceOnly
interface Shipment<T extends Parcel, U extends Inspection> extends ParcelDeck<T>, ParcelSelection<T>, InspectionDeck<U> {}

interface DepotShipment extends Shipment<TrackedParcel, ParcelInspection> { String getId(); }
