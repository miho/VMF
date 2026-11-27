package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.Set;

interface EqualityItem {
    String getCode();

    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.MutableEqualityExchange.CodeEquality")
    boolean equals(Object other);

    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.MutableEqualityExchange.CodeEquality")
    int hashCode();
}

interface EqualityBasket {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) EqualityItem[] getItems();
    Set<EqualityItem> getSelected();
}
