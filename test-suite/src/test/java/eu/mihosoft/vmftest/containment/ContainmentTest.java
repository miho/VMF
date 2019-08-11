package eu.mihosoft.vmftest.containment;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

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