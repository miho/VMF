package eu.mihosoft.vmf.conformance.immutablebehavior.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;

@Immutable
interface ImmutableEqualityValue {
    int getCode();

    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableEqualityOracle.CodeEquality")
    boolean equals(Object other);

    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableEqualityOracle.CodeEquality")
    int hashCode();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableEqualityOracle.CodeEquality")
    int comparisons();
}
