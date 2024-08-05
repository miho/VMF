package eu.mihosoft.vmf.jackson.test.external_types01.vmfmodel;


import eu.mihosoft.vmf.core.ExternalType;

@ExternalType(pkgName = "eu.mihosoft.vmf.jackson.test.external_types01")
interface ExternalEnum { }

interface ExternalTypeModel01 {
    
    String getName();

    String getnameValue();

    ExternalEnum getEnumValue();

    String[] getTags();

    Integer[] getValues();

}
