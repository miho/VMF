package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Multiplicity;
import java.util.List;

interface DefaultPrimary {
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    SelectionTarget[] getQueue();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.summary()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getSummary();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.zulu()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getZulu();
}

interface PassiveConcreteRole {
    @DefaultValue("\"north\"")
    String getRegion();
    int getRank();
}

interface ComposedDefaultSelection extends DefaultPrimary, PassiveConcreteRole {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.InheritedMixedDefaultBindings.ahead()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getAhead();
}

interface ComposedDefaultSelectionLeaf extends ComposedDefaultSelection { }
