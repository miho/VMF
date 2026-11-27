package eu.mihosoft.vmf.conformance.genericbounds.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Multiplicity;

interface DirectLabelSelection extends LabelSlot<LabelView> {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) WorkingLabel[] getOwned();
}
