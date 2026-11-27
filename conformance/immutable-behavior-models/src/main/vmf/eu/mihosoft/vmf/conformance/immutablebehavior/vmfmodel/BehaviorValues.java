package eu.mihosoft.vmf.conformance.immutablebehavior.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;

@Immutable
interface IndependentValue {
    String getText();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.First.Twin")
    int first();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.Second.Twin")
    int second();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.First.Twin")
    int firstAgain();
}

@Immutable
interface MethodValue {
    String getText();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.MethodBinding")
    String render(String prefix);

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.MethodBinding")
    String render(int length);

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.MethodBinding")
    int calls();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.MethodBinding")
    int sum(int... values);
}

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.HookBinding")
interface HookValue {
    String getText();
    int score(int increment);
}

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.UnusedHookBinding")
interface HookOnlyValue {
    int getCount();
}

@InterfaceOnly
interface MetricTrait {
    @GetterOnly int getBase();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.TraitBinding")
    int metric(int delta);
}

@Immutable
interface TraitValue extends MetricTrait {
    String getCode();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.LocalTraitBinding")
    String describe(String suffix);
}

interface MutableTraitValue extends MetricTrait { }

@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmf.conformance.immutablebehavior.ImmutableBehaviorConformance.HookTraitBinding")
interface HookTrait {
    @GetterOnly int getBase();
    int measure(int factor);
}

@InterfaceOnly
interface HookBranch extends HookTrait { }

@Immutable
interface HookTraitValue extends HookBranch {
    String getCode();
}
