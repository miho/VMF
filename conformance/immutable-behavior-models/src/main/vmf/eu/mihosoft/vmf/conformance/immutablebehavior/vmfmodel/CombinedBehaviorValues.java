package eu.mihosoft.vmf.conformance.immutablebehavior.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;

@Immutable
interface LeftBehaviorValue {
    int getLeft();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.CombinedBehaviorOracle.Left")
    int leftCall();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.CombinedBehaviorOracle.Left")
    int preferred();
}

@Immutable
interface RightBehaviorValue {
    int getRight();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.CombinedBehaviorOracle.Right")
    int rightCall();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.CombinedBehaviorOracle.Right")
    int preferred();
}

@Immutable
interface CombinedBehaviorValue extends LeftBehaviorValue, RightBehaviorValue { }

@Immutable
interface ReversedBehaviorValue extends RightBehaviorValue, LeftBehaviorValue { }
