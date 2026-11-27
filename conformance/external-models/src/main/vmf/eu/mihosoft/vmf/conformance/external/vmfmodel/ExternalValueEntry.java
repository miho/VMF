package eu.mihosoft.vmf.conformance.external.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.ExternalType;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;

@ExternalType(pkgName = "java.util")
interface UUID {}

@Annotation(key = "vmf:wire:type", value = "external-entry")
interface ExternalValueEntry {
    String getId();
    UUID getSourceId();
    UUID[] getRelatedIds();

    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    ExternalValueEntry[] getChildren();
}

interface BoundedExternalIds {
    @Multiplicity(lower = 1, upper = 2)
    List<UUID> getIds();
}

@InterfaceOnly
interface ExternalSamples<T> {
    T getLatest();

    @Multiplicity(lower = 1)
    List<T> getSamples();
}

interface GenericExternalIds extends ExternalSamples<UUID> {}
