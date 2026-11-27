package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Multiplicity;
import java.util.List;
import java.util.Set;

interface MixedDefaultPicks {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.MixedDefaultBindings.caption()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getCaption();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.MixedDefaultBindings.favorites()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<SelectionTarget> getFavorites();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.MixedDefaultBindings.numbers()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.MixedDefaultBindings.options()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    VMap<String, Integer> getOptions();
}

@Immutable interface MixedFrozenPicks {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.MixedDefaultBindings.caption()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getCaption();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.MixedDefaultBindings.tokens()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<SelectionToken> getFavorites();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.MixedDefaultBindings.numbers()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();
}
