package eu.mihosoft.vmftest.cross_ref.vmfmodel;

interface EntityOne {
    @Refers(opposite="ref")
    EntityTwo getRef();
}
interface EntityTwo {
    @Refers(opposite="ref")
    EntityOne getRef();
}
