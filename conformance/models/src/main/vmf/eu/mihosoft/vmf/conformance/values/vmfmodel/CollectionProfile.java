package eu.mihosoft.vmf.conformance.values.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

interface CollectionPolicy {
    @Multiplicity(lower = 1, upper = 3)
    VMap<String, Long> getLimits();
}

interface CollectionProfile extends CollectionPolicy {
    String[] getMessages();

    List<Double> getReadings();

    VMap<String, Boolean> getOptions();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3)
    List<String> getPhases();

    @Multiplicity(upper = 3)
    List<Integer> getCounts();

    @Multiplicity(lower = 1, upper = 4)
    Set<Double> getLevels();

    VMap<Double, Character> getMarkers();
}
