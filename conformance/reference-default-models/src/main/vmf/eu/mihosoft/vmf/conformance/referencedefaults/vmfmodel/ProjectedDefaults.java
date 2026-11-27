package eu.mihosoft.vmf.conformance.referencedefaults.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.Multiplicity;
import java.util.List;
import java.util.Set;

interface ProjectionBasis {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.before()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getBefore();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.numbers()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getNumbers();
}

interface ProjectionPrimary extends ProjectionBasis {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.path()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getPath();
}

interface ProjectedQueueRoot {
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.queue()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    SelectionTarget[] getQueue();

    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.summary()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getSummary();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.zulu()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getZulu();
}

interface ProjectedQueueRole extends ProjectedQueueRoot {
    String getNotes();
}

interface ProjectedFavoriteRoot {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.accent()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getAccent();

    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.favorites()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    Set<SelectionTarget> getFavorites();
}

interface ProjectedFavoriteRole extends ProjectedFavoriteRoot {
    int getRank();
}

interface ProjectedCalibrationRoot {
    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.prunes()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getPrunes();
}

interface ProjectedCalibrationRole extends ProjectedCalibrationRoot {
    @Multiplicity(upper = 3)
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.marks()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    List<Integer> getMarks();
}

interface ProjectedDefaults extends ProjectionPrimary, ProjectedQueueRole, ProjectedFavoriteRole, ProjectedCalibrationRole {
    @DefaultValue("\"concrete\"")
    String getLabel();
}

interface ProjectedDefaultsLeaf extends ProjectedDefaults {
    @DefaultValue("eu.mihosoft.vmf.conformance.referencedefaults.ProjectedDefaultBindings.ahead()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    String getAhead();
}
