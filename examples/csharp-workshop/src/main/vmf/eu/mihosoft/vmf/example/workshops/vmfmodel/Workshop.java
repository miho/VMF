package eu.mihosoft.vmf.example.workshops.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Required;

import java.util.Set;

interface Workshop {
    @Contains(opposite = "workshop")
    @Multiplicity(lower = 1, upper = 2, kind = Multiplicity.Kind.ORDERED_SET)
    Station[] getStations();

    Set<Station> getFavorites();
}

interface Station {
    @Required
    String getName();

    @Container(opposite = "stations")
    Workshop getWorkshop();
}

interface WorkshopSite {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    Workshop[] getWorkshops();
}
