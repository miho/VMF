package eu.mihosoft.vmf.conformance.behavior.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;
import java.util.Set;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.SnapshotCollectionBehavior")
interface HookedSnapshotCollections {
    @Multiplicity(upper = 3) List<Integer> getNumbers();
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3) List<String> getOrdered();
    Set<Integer> getFlags();
    VMap<String, Integer> getValues();
    String[] getLegacy();

    int invocation();
    String observedMapEdits();
}

interface SnapshotCollectionDefaults {
    @Multiplicity(upper = 3)
    @DefaultValue("java.util.List.of(7)")
    List<Integer> getDefaulted();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.SnapshotLiteralDefaultBehavior")
interface HookedLiteralCollections {
    @Multiplicity(upper = 3)
    @DefaultValue("java.util.List.of(7)")
    List<Integer> getNumbers();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3)
    @DefaultValue("java.util.List.of(\"ready\")")
    List<String> getOrdered();

    @DefaultValue("java.util.Set.of(3)")
    Set<Integer> getFlags();

    @DefaultValue("java.util.Map.of(\"saved\", 7)")
    VMap<String, Integer> getValues();

    String observedMapEdits();
}
