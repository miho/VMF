package eu.mihosoft.vmf.conformance.immutableancestry.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Immutable;

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.immutableancestry.ImmutableAncestryConformance.BaseBinding")
interface BaseValue {
    int getBase();
    int score(int increment);
    String render(String prefix);
}

@Immutable
interface InheritedValue extends BaseValue {
    String getName();
}

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.immutableancestry.ImmutableAncestryConformance.SpecialBinding")
interface SpecialValue extends BaseValue {
    int getFactor();

    @Override int score(int increment);
    int adjust(int value);
}

@Immutable
interface LeafValue extends SpecialValue {
    String getLabel();
}

interface ValueEnvelope {
    BaseValue getValue();
}
