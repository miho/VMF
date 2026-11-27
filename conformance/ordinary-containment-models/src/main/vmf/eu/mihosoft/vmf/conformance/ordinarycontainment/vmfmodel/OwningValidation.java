package eu.mihosoft.vmf.conformance.ordinarycontainment.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Range;
import eu.mihosoft.vmf.core.Required;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.List;

interface ValidationLayout {
    @Contains ValidationCard[] getEntries();
}

@InterfaceOnly
interface ValidationPolicy<T extends Number> {
    @Multiplicity(lower = 2, upper = 4) List<String> getLabels();
    @Range(min = "1", max = "3") T getWeight();
}

@VMFEquals(VMFEquals.EqualsType.ALL)
interface ValidationCard extends ValidationPolicy<Integer> {
    @Annotation(key = "vmf:jackson:rename", value = "display_title")
    @Required String getTitle();
}
