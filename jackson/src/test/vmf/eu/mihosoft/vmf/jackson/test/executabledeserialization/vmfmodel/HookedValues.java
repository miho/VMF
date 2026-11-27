package eu.mihosoft.vmf.jackson.test.executabledeserialization.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

import java.util.List;

@DelegateTo(className = "eu.mihosoft.vmf.jackson.test.executabledeserialization.DefaultTrace")
interface HookedValues {
    @Multiplicity(upper = 3)
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.jackson.test.executabledeserialization.DefaultTrace.numbers()")
    @Annotation(key = "vmf:jackson:rename", value = "counts")
    List<Integer> getNumbers();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.jackson.test.executabledeserialization.DefaultTrace.settings()")
    VMap<String, Integer> getSettings();
}

@DelegateTo(className = "eu.mihosoft.vmf.jackson.test.executabledeserialization.DefaultTrace")
interface HookedReferences {
    @Multiplicity(upper = 3)
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.jackson.test.executabledeserialization.DefaultTrace.numbers()")
    @Annotation(key = "vmf:jackson:rename", value = "counts")
    List<Integer> getNumbers();

    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @DefaultValue("eu.mihosoft.vmf.jackson.test.executabledeserialization.DefaultTrace.settings()")
    VMap<String, Integer> getSettings();

    @Contains ReferencedChild getChild();

    @Annotation(key = "vmf:jackson:rename", value = "picked")
    ReferencedChild getSelection();
}

@Annotation(key = "vmf:wire:type", value = "referenced-child")
interface ReferencedChild {
    String getLabel();
}

@Annotation(key = "vmf:wire:type", value = "tagged-child")
interface TaggedChild extends ReferencedChild {
    String getTag();
}

@InterfaceOnly
interface MetadataReading {}

interface TextMetadataReading extends MetadataReading {}

interface NumberMetadataReading extends MetadataReading {}

@InterfaceOnly
interface MetadataPort<T extends MetadataReading> {}

interface TextMetadataPort extends MetadataPort<TextMetadataReading> {}

interface NumberMetadataPort extends MetadataPort<NumberMetadataReading> {}

@DelegateTo(className = "eu.mihosoft.vmf.jackson.test.executabledeserialization.DefaultTrace")
interface HookedMetadataPort extends TextMetadataPort {
    String getNote();
}
