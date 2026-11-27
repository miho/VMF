package eu.mihosoft.vmf.conformance.behavior.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaultsBehavior")
interface HookedManagedDefaults {
    HookedManagedDefaults getLink();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaultsBehavior.numbers()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();

    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaultsBehavior.token()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    int getToken();

    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaultsBehavior.values()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    VMap<String, Integer> getValues();

    String observedMapEdits();
}

interface UnhookedManagedDefaults {
    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaultsBehavior.numbers()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();

    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.HookedManagedDefaultsBehavior.values()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    VMap<String, Integer> getValues();
}
