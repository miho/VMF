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
    
    }
}
