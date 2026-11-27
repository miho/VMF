package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Multiplicity;
import java.util.List;

interface ExecutableSelectionBase {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    SelectionTarget[] getQueue();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.summary()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getSummary();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.zulu()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getZulu();
}

interface ExecutableSelectionChild extends ExecutableSelectionBase {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.ahead()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getAhead();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.options()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    VMap<String, Integer> getOptions();
}

interface ExecutableSelectionLeaf extends ExecutableSelectionChild { }

interface ManagedSelectionBase {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    SelectionTarget[] getQueue();
}

interface ManagedSelectionLeaf extends ManagedSelectionBase {
    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.summary()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getEarlier();
}
