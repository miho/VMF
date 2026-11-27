package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Multiplicity;
import java.util.List;
import java.util.Set;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.referencedefaults.RootHookBehavior")
interface RootHookSelection {
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    SelectionTarget[] getQueue();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.earlier()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getEarlier();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.zulu()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getZulu();
}

interface RootHookSelectionLeaf extends RootHookSelection {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.RootHookDefaultsConformance.ahead()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getAhead();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.referencedefaults.SingleHookBehavior")
interface SingleHookSelection {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<SelectionTarget> getQueue();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.earlier()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getEarlier();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.HookReferenceDefaultBindings.zulu()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getZulu();
}
