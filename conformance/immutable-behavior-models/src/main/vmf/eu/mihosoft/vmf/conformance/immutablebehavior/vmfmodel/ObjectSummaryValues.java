package eu.mihosoft.vmf.conformance.immutablebehavior.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;

@Immutable
interface PlainSummary { String getLabel(); }

@Immutable
interface StandaloneSummary { String getLabel(); }

@Immutable
interface NullSummary extends PlainSummary {
    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableToStringOracle.NullBinding") String toString();
}

@Immutable
interface TextSummary {
    String getLabel();
    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableToStringOracle.TextBinding") String toString();
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableToStringOracle.TextBinding") int calls();
}

@Immutable
interface InheritedSummary extends TextSummary { }
