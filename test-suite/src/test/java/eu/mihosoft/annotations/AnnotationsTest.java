package eu.mihosoft.annotations;

import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmftest.annotations.*;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

public class AnnotationsTest {
    @Test
    public void basicAnnotationTest() {
        AnnotatedModel annotatedModel = AnnotatedModel.newInstance();

        List<Annotation> annotations = annotatedModel.vmf().reflect().annotations();

        Assert.assertEquals(2,annotations.size());
        Assert.assertTrue("Not as expected, got: " + annotations.get(0), annotations.get(0).equals("key 1", "my value 1"));
        Assert.assertTrue("Not as expected, got: " + annotations.get(1), annotations.get(1).equals("key 2", "my value 2"));

        List<Annotation> propertyAnnotations = annotatedModel.vmf().reflect().
                propertyByName("name").map(p->p.annotations()).orElse(Collections.EMPTY_LIST);

        Assert.assertEquals(2,propertyAnnotations.size());
        Assert.assertTrue("Not as expected, got: " + propertyAnnotations.get(0),
                propertyAnnotations.get(0).equals("prop key 1", "my prop value 1"));
        Assert.assertTrue("Not as expected, got: " + propertyAnnotations.get(1),
                propertyAnnotations.get(1).equals("prop key 2", "my prop value 2"));

        Assert.assertTrue(annotatedModel.vmf().reflect().annotationByKey("key 1").isPresent());
        Assert.assertTrue(annotatedModel.vmf().reflect().annotationByKey("key 2").isPresent());
        Assert.assertFalse(annotatedModel.vmf().reflect().annotationByKey("key 3").isPresent());

    }

    @Test
    public void multipleAnnotationsPerKeyTest() {
        MultipleAnnotationsPerKey annotatedObject = MultipleAnnotationsPerKey.newInstance();

        List<Annotation> annotations = annotatedObject.vmf().reflect().annotations();

        Assert.assertEquals(3,annotations.size());
        Assert.assertTrue("Not as expected, got: " + annotations.get(0), annotations.get(0).equals("key 1", "my value 1"));
        Assert.assertTrue("Not as expected, got: " + annotations.get(1), "key 2".equals(annotations.get(1).getKey()));
        Assert.assertTrue("Not as expected, got: " + annotations.get(2), "key 2".equals(annotations.get(1).getKey()));

        List<Annotation> key1 = annotatedObject.vmf().reflect().annotationsByKey("key 1");
        List<Annotation> key2 = annotatedObject.vmf().reflect().annotationsByKey("key 2");

        Assert.assertTrue("Size differs. got " + key1.size(), key1.size()==1);
        Assert.assertTrue("Size differs. got " + key2.size(), key2.size()==2);


    }
    @Test
    public void annotationInheritanceTest() {


        AnnotationInheritance1Parent annotatedObjectParent = AnnotationInheritance1Parent.newInstance();

        List<Annotation> annotations = annotatedObjectParent.vmf().reflect().annotations();

        Assert.assertEquals(2,annotations.size());
        Assert.assertTrue("Not as expected, got: " + annotations.get(0), annotations.get(0).equals("key 1", "my parent value 1"));
        Assert.assertTrue("Not as expected, got: " + annotations.get(1), annotations.get(1).equals("key 2", "my parent value 2"));

        AnnotationInheritance1Child annotatedObjectChild = AnnotationInheritance1Child.newInstance();

        annotations = annotatedObjectChild.vmf().reflect().annotations();

        Assert.assertEquals(2,annotations.size());
        Assert.assertTrue("Not as expected, got: " + annotations.get(0), annotations.get(0).equals("key 1", "my child value 1"));
        Assert.assertTrue("Not as expected, got: " + annotations.get(1), annotations.get(1).equals("key 2", "my child value 2"));


    }

    @Test
    public void annotationPropertyInheritanceTest() {

        AnnotationInheritance2Parent annotatedObjectParent = AnnotationInheritance2Parent.newInstance();

        List<Annotation> annotations = annotatedObjectParent.vmf().reflect().
                propertyByName("name").map(p->p.annotations()).orElse(Collections.EMPTY_LIST);

        Assert.assertEquals(2,annotations.size());
        Assert.assertTrue("Not as expected, got: " + annotations.get(0), annotations.get(0).equals("key 1", "my parent value 1"));
        Assert.assertTrue("Not as expected, got: " + annotations.get(1), annotations.get(1).equals("key 2", "my parent value 2"));

        AnnotationInheritance2Child annotatedObjectChild = AnnotationInheritance2Child.newInstance();

        annotations = annotatedObjectChild.vmf().reflect().
                propertyByName("name").map(p->p.annotations()).orElse(Collections.EMPTY_LIST);

        Assert.assertEquals(2,annotations.size());
        Assert.assertTrue("Not as expected, got: " + annotations.get(0), annotations.get(0).equals("key 1", "my child value 1"));
        Assert.assertTrue("Not as expected, got: " + annotations.get(1), annotations.get(1).equals("key 2", "my child value 2"));


    }
}
