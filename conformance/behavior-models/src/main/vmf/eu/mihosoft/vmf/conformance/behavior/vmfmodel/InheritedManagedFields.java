package eu.mihosoft.vmf.conformance.behavior.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;

@InterfaceOnly
interface InheritedManagedFields {
    @Multiplicity(upper = 3)
    @Annotation(key = "vmf:jackson:rename", value = "counts")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior.numbers()")
    List<Integer> getNumbers();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior.settings()")
    VMap<String, Integer> getSettings();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior")
interface InheritedManagedValues extends InheritedManagedFields { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior")
interface InheritedManagedReferences extends InheritedManagedFields {
    @Contains InheritedManagedChild getChild();

    @Annotation(key = "vmf:jackson:rename", value = "picked")
    InheritedManagedChild getSelection();
}

interface InheritedManagedDerived extends InheritedManagedReferences { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior")
interface InheritedManagedOverridden extends InheritedManagedReferences { }

interface InheritedManagedChild {
    String getLabel();
}

interface RedeclaredManagedReplacement extends InheritedManagedReferences {
    @Multiplicity(upper = 3)
    @Annotation(key = "vmf:jackson:rename", value = "counts")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior.replacementNumbers()")
    List<Integer> getNumbers();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior.replacementSettings()")
    VMap<String, Integer> getSettings();
}

interface RedeclaredManagedEmpty extends InheritedManagedReferences {
    @Multiplicity(upper = 3)
    @Annotation(key = "vmf:jackson:rename", value = "counts")
    @DefaultValue("java.util.List.of()")
    List<Integer> getNumbers();

    @DefaultValue("java.util.Map.of()")
    VMap<String, Integer> getSettings();
}

interface RedeclaredManagedNone extends InheritedManagedReferences {
    @Multiplicity(upper = 3)
    @Annotation(key = "vmf:jackson:rename", value = "counts")
    List<Integer> getNumbers();

    VMap<String, Integer> getSettings();
}

interface RedeclaredManagedMixed extends InheritedManagedReferences {
    @Multiplicity(upper = 3)
    @Annotation(key = "vmf:jackson:rename", value = "counts")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.conformance.behavior.InheritedManagedDefaultsBehavior.replacementNumbers()")
    List<Integer> getNumbers();

    VMap<String, Integer> getSettings();
}
