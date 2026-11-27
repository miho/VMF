package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.referencedefaults.ProjectedConstructionBehavior")
interface HookedProjection extends ProjectionPrimary, ProjectedQueueRole, ProjectedFavoriteRole {
    String describe(String prefix);
}

interface HookedProjectionLeaf extends HookedProjection {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.ahead()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getAhead();
}
