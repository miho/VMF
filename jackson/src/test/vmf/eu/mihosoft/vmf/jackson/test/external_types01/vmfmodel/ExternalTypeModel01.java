package eu.mihosoft.vmf.jackson.test.external_types01.vmfmodel;


import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.ExternalType;

@ExternalType(pkgName = "eu.mihosoft.vmf.jackson.test.external_types01")
@Annotation(key = "vmf:csharp:external-value-type", value = "Eu.Mihosoft.Vmf.Jackson.Test.External_types01.ExternalEnum")
interface ExternalEnum { }

@Annotation(key = "vmf:wire:type", value = "external-type-model-01")
interface ExternalTypeModel01 {
    
    String getName();

    String getnameValue();

    ExternalEnum getEnumValue();

    String[] getTags();

    Integer[] getValues();

}
