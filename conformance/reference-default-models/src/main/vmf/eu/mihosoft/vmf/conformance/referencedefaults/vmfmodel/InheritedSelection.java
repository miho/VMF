package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.Set;

interface InheritedSelection {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedReferenceDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    SelectionTarget[] getQueue();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedReferenceDefaultBindings.saved()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<SelectionTarget> getSaved();
}

interface CuratedSelection extends InheritedSelection {
    @DefaultValue("\"curated\"")
    String getLabel();
}

interface PublishedSelection extends CuratedSelection { }
