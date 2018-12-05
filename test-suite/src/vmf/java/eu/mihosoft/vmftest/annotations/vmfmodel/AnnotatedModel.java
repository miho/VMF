package eu.mihosoft.vmftest.annotations.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;

@Annotation(key = "key1", value = "my value 1")
@Annotation(key = "key1", value = "my value 1")
interface AnnotatedModel {

    @Annotation(key="prop key 1", value = "my value 2")
    String getName();
}
