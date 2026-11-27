package eu.mihosoft.vmf.conformance.ordinarycontainment.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.Set;

interface ReadingRoom {
    @Contains @Keyed(by = "name") Shelf[] getShelves();
    Volume getFeatured();
}

interface Shelf {
    String getName();

    @Contains(opposite = "shelf")
    @Multiplicity(upper = 2)
    Set<Volume> getVolumes();
}

interface Volume {
    String getName();
    @Container(opposite = "volumes") Shelf getShelf();
}
