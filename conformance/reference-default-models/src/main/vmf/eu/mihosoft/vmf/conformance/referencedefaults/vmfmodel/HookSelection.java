package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Multiplicity;
import java.util.List;

interface HookSelectionBase {
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    SelectionTarget[] getQueue();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.zulu()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getZulu();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.referencedefaults.HookedReferenceBehavior")
interface HookSelection extends HookSelectionBase {
    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.earlier()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getEarlier();
}

interface HookSelectionLeaf extends HookSelection { }
