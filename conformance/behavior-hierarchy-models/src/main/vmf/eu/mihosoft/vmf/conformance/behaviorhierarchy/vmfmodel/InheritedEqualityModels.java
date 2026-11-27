package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.Set;

interface InheritedEqualityBase {
    int getCode();

    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.InheritedEqualityExchange.CodeEquality")
    boolean equals(Object other);

    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.InheritedEqualityExchange.CodeEquality")
    int hashCode();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.InheritedEqualityExchange.CodeEquality")
    int comparisons();
}

interface InheritedEqualityItem extends InheritedEqualityBase {
    int getRank();
}

interface InheritedEqualityBasket {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) InheritedEqualityItem[] getItems();
    Set<InheritedEqualityItem> getSelected();
}
