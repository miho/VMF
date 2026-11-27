package eu.mihosoft.vmf.conformance.immutablebehavior.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;

@Immutable
interface PrimaryData { String getName(); }

@Immutable
interface SecondaryBehavior {
    int getMetric();
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableSecondaryOracle.Counter") int counter();
}

@Immutable
interface ComposedValue extends PrimaryData, SecondaryBehavior { }

@Immutable
interface ComposedChild extends ComposedValue { }

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableSecondaryOracle.HookCounter")
interface HookedSecondary {
    int getScale();
    int counter();
}

@Immutable
interface HookedComposed extends PrimaryData, HookedSecondary { }

interface HookedValueEnvelope {
    HookedSecondary getFirst();
    HookedComposed getSecond();
}
