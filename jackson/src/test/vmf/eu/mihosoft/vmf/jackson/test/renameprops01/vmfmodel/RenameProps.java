package eu.mihosoft.vmf.jackson.test.renameprops01.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;

interface RenamePropsModel {

    // We want to prevent the property from being called "cOMPort" in the generated JSON
    @Annotation(key = "vmf:jackson:rename", value = "comPort")
    String getCOMPort();

}
