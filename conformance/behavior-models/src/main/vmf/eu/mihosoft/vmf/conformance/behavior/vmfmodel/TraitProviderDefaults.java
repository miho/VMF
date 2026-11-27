package eu.mihosoft.vmf.conformance.behavior.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface TraitDefaultLabel {
    @GetterOnly
    @Annotation(key = "vmf:jackson:rename", value = "label")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.TraitDefaultProviders.label()")
    String getDisplayLabel();
}

@Immutable
interface DefaultedTraitValue extends TraitDefaultLabel { }

@Immutable
interface RankedTraitValue extends TraitDefaultLabel {
    int getRank();
}

@Immutable
interface LiteralTraitValue extends TraitDefaultLabel {
    @GetterOnly
    @Annotation(key = "vmf:jackson:rename", value = "label")
    @DefaultValue("\"local\"")
    String getDisplayLabel();
}

@Immutable
interface ClearedTraitValue extends TraitDefaultLabel {
    @GetterOnly
    @Annotation(key = "vmf:jackson:rename", value = "label")
    String getDisplayLabel();
}
