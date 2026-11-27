package eu.mihosoft.vmf.conformance.immutablebehavior.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;

@Immutable
interface ValueResult { String getId(); }

@Immutable
interface NarrowValueResult extends ValueResult { int getRank(); }

@Immutable
interface BaseValue {
    ValueResult getStored();
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableCovariantOracle.BaseBinding") ValueResult evaluate();
}

@Immutable
interface ChildValue extends BaseValue {
    NarrowValueResult getNarrowStored();
    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableCovariantOracle.ChildBinding") NarrowValueResult evaluate();
}

@Immutable
interface GrandchildValue extends ChildValue { }
