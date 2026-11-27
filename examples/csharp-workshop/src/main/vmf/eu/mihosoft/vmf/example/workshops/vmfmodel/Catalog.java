package eu.mihosoft.vmf.example.workshops.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

@InterfaceOnly
interface Product {
    String getSku();
}

@Annotation(key = "vmf:wire:type", value = "instrument")
interface Instrument extends Product {
}

@Annotation(key = "vmf:wire:type", value = "thermometer")
interface Thermometer extends Instrument {
    String getUnit();
}

@InterfaceOnly
interface Shelf<T extends Product> {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @Annotation(key = "vmf:jackson:rename", value = "items")
    T[] getProducts();

    T getFeatured();
}

interface Catalog extends Shelf<Instrument> {
}
