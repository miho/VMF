package eu.mihosoft.vmf.conformance.values.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.Set;

interface CollectionApplyPeer {
    @Annotation(key = "vmf:jackson:rename", value = "peer_limits")
    @Multiplicity(upper = 2)
    VMap<String, Long> getLimits();

    @Multiplicity(upper = 3)
    Set<Double> getLevels();
}

interface CollectionApplyIncompatible {
    VMap<String, String> getLimits();

    Set<Boolean> getLevels();
}
