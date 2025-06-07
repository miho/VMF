package eu.mihosoft.vmf.jackson.test.external_types02.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;

import java.util.Map;

public interface ExternalTypeModel02 {
    @DefaultValue("new java.util.HashMap<>()")
    Map<String,String> getStringMap();

    @DefaultValue("new java.util.HashMap<>()")
    Map<String,Object> getObjectMap();
}
