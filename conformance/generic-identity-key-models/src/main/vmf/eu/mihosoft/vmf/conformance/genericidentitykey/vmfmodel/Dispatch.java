package eu.mihosoft.vmf.conformance.genericidentitykey.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

interface Dispatch {
    @Contains RouteManifest getManifest();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Location[] getTargets();
}

@InterfaceOnly interface LocationBase {
    @IgnoreEquals String getCode();
    String getName();
}

@VMFEquals(VMFEquals.EqualsType.ALL)
interface Location extends LocationBase {}

@InterfaceOnly interface LocationKey<K extends LocationBase> {
    @Annotation(key = "vmf:jackson:rename", value = "destination")
    K getLocation();
}

@InterfaceOnly interface RouteEntry extends LocationKey<Location> { String getCode(); }

interface Shipment extends RouteEntry {}

@InterfaceOnly
interface Routes<T extends RouteEntry> {
    @Contains @Keyed(by = "location")
    @Annotation(key = "vmf:jackson:rename", value = "shipments")
    T[] getEntries();
}

@InterfaceOnly interface ForwardedRoutes<X extends RouteEntry> extends Routes<X> {}

interface RouteManifest extends ForwardedRoutes<Shipment> {}

@Immutable @VMFEquals(VMFEquals.EqualsType.ALL)
interface PermitCode { String getLabel(); }

@InterfaceOnly interface PermitRecord { PermitCode getProof(); }

interface Permit extends PermitRecord {}

@InterfaceOnly interface PermitShelf<T extends PermitRecord> {
    @Contains @Keyed(by = "proof") T[] getPermits();
}

interface Gate extends PermitShelf<Permit> {}
