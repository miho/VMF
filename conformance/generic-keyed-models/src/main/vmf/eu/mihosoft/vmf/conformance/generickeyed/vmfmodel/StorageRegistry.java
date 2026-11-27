package eu.mihosoft.vmf.conformance.generickeyed.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

interface StorageRegistry {
    @Contains Warehouse getPrimary();
    @Contains Warehouse getReserve();
}

@InterfaceOnly interface StorageRecord {
    String getIdentity();
    String getSlot();
}

@InterfaceOnly
interface Storage<T extends StorageRecord> {
    @Contains @Keyed(by = "slot")
    @Annotation(key = "vmf:jackson:rename", value = "entries")
    List<T> getOwned();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) T[] getQueue();
    Set<T> getFavorites();
    T getCurrent();
}

@InterfaceOnly interface IncomingStorage<T extends StorageRecord> extends Storage<T> {
    String getName();
}

@InterfaceOnly interface OutgoingStorage<T extends StorageRecord> extends Storage<T> {}

interface Warehouse extends IncomingStorage<WarehouseEntry>, OutgoingStorage<WarehouseEntry> {}

interface WarehouseEntry extends StorageRecord {
    String getDescription();
}
