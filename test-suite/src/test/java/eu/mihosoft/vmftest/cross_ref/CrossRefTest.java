package eu.mihosoft.vmftest.cross_ref;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import java.util.concurrent.atomic.AtomicInteger;

import eu.mihosoft.vmf.runtime.core.VObject;

public class CrossRefTest {

    static AtomicInteger countChangeEvents(VObject o) {
        // count total change events
        AtomicInteger numChanges= new AtomicInteger(0);
        o.vmf().changes().addListener(change -> {
            System.out.println("change: ");
            System.out.println(" -> obj: " + o.getClass() + ", value: " + System.identityHashCode(o));
            System.out.print(" -> prop="+change.propertyName() + ": ");
            
            if(change.propertyChange().isPresent()) {
                System.out.println(", oldVal= " + System.identityHashCode(change.propertyChange().get().oldValue())
                                 + ", newVal= " + System.identityHashCode(change.propertyChange().get().newValue()));
            } else {
                System.out.println("<not present>");
            }

            numChanges.getAndIncrement();
        });

        return numChanges;
    }

    @Test
    public void singleRefTest() {
        {

            System.out.println("--- TEST singleRefTest");
            EntityOneA entityOneA = EntityOneA.newInstance();
            EntityTwoA entityTwoA = EntityTwoA.newInstance();

            final AtomicInteger numEvtOneA = countChangeEvents(entityOneA);
            final AtomicInteger numEvtTwoA = countChangeEvents(entityTwoA);

            entityOneA.setRef(entityTwoA);

            assertThat("opposite ref must be set to ref", entityTwoA.getRef(), equalTo(entityOneA));

            assertThat("there's exactly one property 'ref' change", numEvtOneA.get(), equalTo(1));
            assertThat("there's exactly one property 'ref' change", numEvtTwoA.get(), equalTo(1));
        }

        {
            EntityOneA entityOneA = EntityOneA.newInstance();
            EntityTwoA entityTwoA = EntityTwoA.newInstance();

            entityTwoA.setRef(entityOneA);

            assertThat("opposite ref must be set to ref", entityOneA.getRef(), equalTo(entityTwoA));
        }
    }

    @Test
    public void singleMultipleRefTest() {

        System.out.println("--- TEST singleMultipleRefTest");

        {
            EntityOneB entityOneB = EntityOneB.newInstance();
            EntityTwoB entityTwoB = EntityTwoB.newInstance();

            entityTwoB.getRefs().add(entityOneB);

            assertThat("opposite ref must be set to ref", entityOneB.getRef(), equalTo(entityTwoB));
        }

        {
            EntityOneB entityOneB = EntityOneB.newInstance();
            EntityTwoB entityTwoB = EntityTwoB.newInstance();

            entityOneB.setRef(entityTwoB);

            assertThat("opposite refs must contain ref", entityTwoB.getRefs(), contains(entityOneB));
        }
    }
    @Test
    public void multipleMultipleRefTest() {

        System.out.println("--- TEST multipleMultipleRefTest");

        {
            EntityOneC entityOneC = EntityOneC.newInstance();
            EntityTwoC entityTwoC = EntityTwoC.newInstance();

            entityOneC.getRefs().add(entityTwoC);
            assertThat("opposite refs must contain ref", entityTwoC.getRefs(), contains(entityOneC));
        }
        {
            EntityOneC entityOneC = EntityOneC.newInstance();
            EntityTwoC entityTwoC = EntityTwoC.newInstance();

            entityTwoC.getRefs().add(entityOneC);
            assertThat("opposite refs must contain ref", entityOneC.getRefs(), contains(entityTwoC));
        }
    }
}
