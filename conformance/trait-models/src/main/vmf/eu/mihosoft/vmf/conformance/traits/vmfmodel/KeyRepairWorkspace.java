package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;

interface KeyRepairItem {
    String getKey();
}

interface KeyRepairWorkspace {
    @Contains RoleKey[] getEarlier();
    @Contains @Keyed(by = "key") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    KeyRepairItem[] getLater();
}
