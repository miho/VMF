package eu.mihosoft.vmftest.annotations.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;

@Annotation(key = "key 1", value = "my value 1")
@Annotation(key = "key 2", value = "my value 2")
interface AnnotatedModel {

    @Annotation(key="prop key 1", value = "my prop value 1")
    @Annotation(key="prop key 2", value = "my prop value 2")
    String getName();
}


@Annotation(key = "key 1", value = "my value 1")
@Annotation(key = "key 2", value = "my value 2")
@Annotation(key = "key 2", value = "my value 3")
interface MultipleAnnotationsPerKey {

}


@Annotation(key = "key 1", value = "my parent value 1")
@Annotation(key = "key 2", value = "my parent value 2")
interface AnnotationInheritance1Parent {

}

@Annotation(key = "key 1", value = "my child value 1")
@Annotation(key = "key 2", value = "my child value 2")
interface AnnotationInheritance1Child {

}


interface AnnotationInheritance2Parent {
    @Annotation(key = "key 1", value = "my parent value 1")
    @Annotation(key = "key 2", value = "my parent value 2")
    String getName();
}


interface AnnotationInheritance2Child {
    @Annotation(key = "key 1", value = "my child value 1")
    @Annotation(key = "key 2", value = "my child value 2")
    String getName();
}
