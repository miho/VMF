package eu.mihosoft.vmf.jackson.test.external_types02.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DefaultValue;

import java.util.Map;

@Annotation(key = "vmf:wire:type", value = "external-type-model-02")
public interface ExternalTypeModel02 {
    @DefaultValue("new java.util.HashMap<>()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    Map<String,String> getStringMap();

    @DefaultValue("new java.util.HashMap<>()")
    @Annotation(key = "vmf:csharp:bind-default", value = "true")
    @Annotation(key = "vmf:typescript:bind-default", value = "true")
    Map<String,Object> getObjectMap();
}
