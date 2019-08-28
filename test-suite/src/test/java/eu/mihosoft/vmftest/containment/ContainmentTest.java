package eu.mihosoft.vmftest.containment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.Matchers.isIn;

import java.util.Arrays;

import org.junit.Test;

public class ContainmentTest {
    @Test
    public void containmentTest() {

        // containment should be unique

        // first check that containment works
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.setElement1(e);
        assertThat(e.getParentOne(), equalTo(ca));

        // if we set to second container instance...
        ContainerOne cb = ContainerOne.newInstance();

        // ...should work like before and...
        cb.setElement1(e);
        assertThat(e.getParentOne(), equalTo(cb));

        // ...should unregister from previous container
        assertThat(ca.getElement1(), equalTo(null));
    }

    @Test
    public void containmentMultiplePropsTest1() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // case 1: containments with opposites only

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.setElement1(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before and...
        cb.setElement2(e);
        assertThat(e.getParentTwo(), equalTo(cb));

        // ...should unregister from previous container
        assertThat(ca.getElement1(), equalTo(null));
    }

    @Test
    public void containmentMultiplePropsTest2() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // case 2: mixing containments with and without opposites

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.setElement1(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before, i.e.,
        cb.setElement(e);

        // ...should unregister from previous container
        assertThat(ca.getElement1(), equalTo(null));
    }

    @Test
    public void containmentMultiplePropsTest3() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // case 3: mixing containments with and without opposites
        //        (changed order compared to case 1)

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.setElement(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before, i.e.,
        cb.setElement2(e);

        // ...should unregister from previous container
        assertThat(ca.getElement(), equalTo(null));
    }

    @Test
    public void containmentMultiplePropsTest4() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // case 4: mixing containments with and without opposites
        //         -> first without opposites and then element list with opposite

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.setElement(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before, i.e.,
        cb.getElements2a().add(e);

        // ...should unregister from previous container
        assertThat(ca.getElement(), equalTo(null));
    }

    @Test
    public void containmentMultiplePropsTest5() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // case 5: mixing containments without opposites
        //         (first single prop then collection property (list) )

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.setElement(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before, i.e.,
        cb.getElements2().add(e);

        // ...should unregister from previous container
        assertThat(ca.getElement(), equalTo(null));
    }

    @Test
    public void containmentMultiplePropsTest6() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // case 6: mixing containments without opposites
        //         (one list with opposite, the opther without)

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.getElements1().add(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before, i.e.,
        cb.getElements2a().add(e);

        // ...should unregister from previous container
        assertThat(e, not(isIn(ca.getElements1())));
    }

    @Test
    public void containmentMultiplePropsTest7() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // case 6: mixing containments with and without opposites
        //         (one list with opposite, the other without,
        //          reverse order compared to case 7)

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.getElements1a().add(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before, i.e.,
        cb.getElements2().add(e);

        // ...should unregister from previous container
        assertThat(e, not(isIn(ca.getElements1a())));
        assertThat(e, isIn(cb.getElements2()));
    }

    @Test
    public void containmentMultiplePropsTestNoOpposite() {

        // containment should be unique among multiple properties
        // of container instances of potentially different type

        // containments without opposites only

        // first register with first container instance
        ContainerOne ca = ContainerOne.newInstance();
        Element e = Element.newInstance();
        ca.setElement(e);

        // if we set it to the second container instance...
        ContainerTwo cb = ContainerTwo.newInstance();

        // ...should work like before and...
        cb.setElement(e);

        // ...should unregister from previous container
        assertThat(ca.getElement(), equalTo(null));
    }
}