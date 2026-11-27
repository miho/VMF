package eu.mihosoft.vmf.conformance.hierarchy.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface ManifestRevision<N extends Number> {
    N getRevision();
}

@InterfaceOnly
interface ManifestStorage<T extends Parcel, V, N extends Number> extends ParcelDeck<T>, ManifestRevision<N> {
    VMap<String, V> getLabels();
}

@InterfaceOnly
interface TelemetryManifest<T extends Parcel, V, N extends Number> extends ManifestStorage<T, V, N>, ParcelSelection<T> {}

interface InstrumentedShipment extends TelemetryManifest<TrackedParcel, String, Long> {
    String getId();
}
