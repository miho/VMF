package eu.mihosoft.vmf.conformance.redeclarations.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Range;

import java.util.List;

interface SettingCatalog {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) Settings[] getEntries();

    FrozenSetting getFrozen();
}

@InterfaceOnly
interface MixedSettings {
    @GetterOnly @DefaultValue("\"origin\"")
    @Annotation(key = "vmf:jackson:rename", value = "display_name")
    String getName();

    @DefaultValue("2") @Range(min = "1", max = "9") int getLevel();

    @GetterOnly @Multiplicity(upper = 3) List<Integer> getMeasures();
}

interface Settings extends MixedSettings { }

interface Reconfigured extends Settings {
    @Override @DefaultValue("\"reconfigured\"") String getName();
    @Override @DefaultValue("4") @Range(min = "2", max = "8") int getLevel();
}

interface Unconfigured extends Settings {
    @Override String getName();
    @Override int getLevel();
}

interface ExplicitNull extends Settings {
    @Override @DefaultValue("null")
    @Annotation(key = "vmf:jackson:rename", value = "optional_name")
    String getName();

    @Override @DefaultValue("0") int getLevel();
}

@Immutable
interface FrozenSetting {
    @DefaultValue("\"original\"") String getLabel();
    int getLimit();
}

@Immutable
interface RevisedSetting extends FrozenSetting {
    @Override @DefaultValue("null")
    @Annotation(key = "vmf:jackson:rename", value = "caption")
    String getLabel();

    @Override @DefaultValue("6") int getLimit();
}
