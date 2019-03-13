package eu.mihosoft.vmftest.cross_ref.vmfmodel;

import eu.mihosoft.vmf.core.Refers;

interface EntityOne {
    @Refers(opposite="ref")
    EntityTwo getRef();
}
interface EntityTwo {
    @Refers(opposite="ref")
    EntityOne getRef();
}
