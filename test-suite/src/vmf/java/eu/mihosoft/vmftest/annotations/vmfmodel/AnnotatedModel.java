package eu.mihosoft.vmftest.annotations.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;

@Annotation(key = "key 1", value = "my value 1")
@Annotation(key = "key 2", value = "my value 2")
interface AnnotatedModel {

    @Annotation(key="prop key 1", value = "my prop value 1")
    @Annotation(key="prop key 2", value = "my prop value 2")
    String getName();
}
