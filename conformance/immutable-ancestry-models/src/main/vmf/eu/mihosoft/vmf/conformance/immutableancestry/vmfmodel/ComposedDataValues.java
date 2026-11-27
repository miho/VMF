package eu.mihosoft.vmf.conformance.immutableancestry.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.VMFEquals;

@Immutable
interface ComposedValueRoot {
    @DefaultValue("\"shared\"")
    String getTitle();
}

@Immutable
interface PrimaryDataValue extends ComposedValueRoot {
    int getRank();
}

@Immutable
interface SecondaryDataValue extends ComposedValueRoot {
    @DefaultValue("13")
    long getWeight();
}

@Immutable
interface JoinedDataValue extends PrimaryDataValue, SecondaryDataValue {
    String getName();
}

@Immutable
interface FrozenJoinedSelection {
    SecondaryDataValue getSelected();
    SecondaryDataValue[] getCandidates();
}

interface JoinedDataEnvelope {
    PrimaryDataValue getPrimary();
    SecondaryDataValue getSecondary();
    SecondaryDataValue[] getSelections();
}

@InterfaceOnly
interface WithDisplayLabel {
    @GetterOnly @DefaultValue("\"untitled\"") String getLabel();
}

@InterfaceOnly
interface WithTooltipText {
    @GetterOnly @DefaultValue("\"\"") String getTooltip();
}

@Immutable @VMFEquals
interface DescribedValue extends WithDisplayLabel, WithTooltipText {
    @Override @DefaultValue("\"item\"") @Annotation(key = "vmf:jackson:rename", value = "display_label") String getLabel();
    @Override @DefaultValue("\"\"") @IgnoreEquals String getTooltip();
}
