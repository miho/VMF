package eu.mihosoft.vmftest.cross_ref;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class CrossRefTest {
    @Test
    public void singleRefTest() {
        {
            EntityOneA entityOneA = EntityOneA.newInstance();
            EntityTwoA entityTwoA = EntityTwoA.newInstance();

            entityOneA.setRef(entityTwoA);

            assertThat("opposite ref must be set to ref", entityTwoA.getRef(), equalTo(entityOneA));
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
