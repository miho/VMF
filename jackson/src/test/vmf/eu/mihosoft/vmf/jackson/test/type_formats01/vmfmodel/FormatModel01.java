package eu.mihosoft.vmf.jackson.test.type_formats01.vmfmodel;

import eu.mihosoft.vmf.core.*;

@ExternalType(pkgName = "eu.mihosoft.vmf.jackson.test.type_formats01")
interface PetType {
}

interface FormatModel01 {
    @Contains(opposite = "model")
    @Annotation(key = "vmf:jackson:schema:format", value = "table")
    @Annotation(key = "vmf:jackson:schema:uniqueItems", value = "true")
    Pet[] getPets();
}

interface Pet {
    @DefaultValue("eu.mihosoft.vmf.jackson.test.type_formats01.PetType.UNKNOWN")
    PetType getType();
    String getName();
    int getAge();
    @Container(opposite = "pets")
    FormatModel01 getModel();

    @Annotation(key = "vmf:jackson:schema:format", value = "color")
    @DefaultValue("\"#ffa500\"")
    String getColor();

    @Annotation(key = "vmf:jackson:schema:inject", value = "\"title\": \"My Title\"")
    String getOtherProperty();

    @Contains(opposite = "owner")
    @Annotation(key= "vmf:jackson:schema:format", value = "table")
    Prop[] getProperties();
}

interface Prop {
    String getKey();
    String getValue();
    @Container(opposite = "properties")
    Pet getOwner();
}


