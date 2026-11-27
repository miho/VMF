package eu.mihosoft.vmf.portable.references.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Refers;

// From test-suite vmftest/cross_ref (CrossRefTest, 2019; OneToOneReassignmentTest).
interface EntityOneA {
    String getName();

    @Refers(opposite = "ref")
    EntityTwoA getRef();
}

interface EntityTwoA {
    String getName();

    @Refers(opposite = "ref")
    EntityOneA getRef();
}

interface EntityOneB {
    String getName();

    @Refers(opposite = "refs")
    EntityTwoB getRef();
}

interface EntityTwoB {
    String getName();

    @Refers(opposite = "ref")
    EntityOneB[] getRefs();
}

interface EntityOneC {
    String getName();

    @Refers(opposite = "refs")
    EntityTwoC[] getRefs();
}

interface EntityTwoC {
    String getName();

    @Refers(opposite = "refs")
    EntityOneC[] getRefs();
}

interface Partner {
    String getName();

    @Refers(opposite = "partner")
    Partner getPartner();
}

// Plain references without an opposite next to a containment owner.
interface Store {
    @Contains
    Item[] getStock();
}

interface Holder {
    Item getItem();

    Item[] getItems();
}

interface Item {
    String getName();
}
