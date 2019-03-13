package eu.mihosoft.vmftest.cross_ref.vmfmodel;

import eu.mihosoft.vmf.core.Refers;

interface EntityOneA {
    @Refers(opposite="ref")
    EntityTwoA getRef();
}
interface EntityTwoA {
    @Refers(opposite="ref")
    EntityOneA getRef();
}


interface EntityOneB {
    @Refers(opposite="refs")
    EntityTwoB getRef();
}
interface EntityTwoB {
    @Refers(opposite="ref")
    EntityOneB[] getRefs();
}

interface EntityOneC {
    @Refers(opposite="refs")
    EntityTwoC[] getRefs();
}
interface EntityTwoC {
    @Refers(opposite="refs")
    EntityOneC[] getRefs();
}