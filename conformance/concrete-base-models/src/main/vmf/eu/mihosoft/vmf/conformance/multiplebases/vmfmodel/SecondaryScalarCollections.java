package eu.mihosoft.vmf.conformance.multiplebases.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

interface CollectionHeader {
    long getSequence();
}

interface CollectionRole {
    List<String> getLabels();

    @Multiplicity(upper = 3)
    VMap<String, Long> getLimits();

    Set<Double> getLevels();
}

interface JoinedCollections extends CollectionHeader, CollectionRole { }
