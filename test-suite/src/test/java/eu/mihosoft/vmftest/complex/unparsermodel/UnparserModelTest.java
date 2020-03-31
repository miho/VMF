package eu.mihosoft.vmftest.complex.unparsermodel;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vmftest.complex.unparsermodel.Alternative;
import org.junit.Assert;
import org.junit.Test;
import vjavax.observer.collection.CollectionChangeEvent;
import vjavax.observer.collection.CollectionChangeListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class UnparserModelTest {
    @Test
    public void containmentWithInheritanceTest1() {

        // test containers with inheritance involved.
        // this used to be a problem since property id was statically
        // computed even though the id's depend on the actual
        // type only known at runtime

        {
            Alternative alternative = Alternative.newInstance();
            UPSubRuleElement sre = UPSubRuleElement.newInstance();
            alternative.getElements().add(sre);

            Assert.assertNotNull(sre.getParentAlt());
        }

        {
            Alternative alternative = Alternative.newInstance();
            UPSubRuleElement sre = UPSubRuleElement.newInstance();
            sre.setParentAlt(alternative);

            Assert.assertNotNull(sre.getParentAlt());
            assertThat("Elements should contain sre", alternative.getElements(), hasItem(sre));

            Alternative a1 = Alternative.newInstance();

            sre.getAlternatives().add(a1);

            assertThat("Alternative a1 should have sre as parent", a1.getParentRule(), equalTo(sre));

        }
    }

    @Test
    public void testRemoveDuringAddEventTest() {

        RuleClass cls = RuleClass.newBuilder().withName("RC1").build();

        Property pa1 = Property.newBuilder().withName("pa").build();
        Property pa2 = Property.newBuilder().withName("pa").build();

        cls.getProperties().addAll(Arrays.asList(pa1,pa2));
        cls.getProperties().addAll(Arrays.asList(pa1,pa2));

        cls.getProperties().addChangeListener(evt -> {
            // remove duplicate properties
            for(Property p1 : evt.added().elements()) {
                for(Property p2 : new ArrayList<>(evt.source())) {
                    if(p1!=p2 && Objects.equals(p1.getName(),p2.getName())) {
                        evt.source().remove(p1);
                    }
                }
            }
        });

        for(Property p : cls.getProperties()) {
            System.out.println("> " + p.getName());
        }

        assertThat("Properties", cls.getProperties(), contains(pa1,pa2));
    }



}


