package eu.mihosoft.vmftest.lazyinit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

public class LazyInitTest {
    @Test public void testLazyInitOfLists1() {
        Obj o1 = Obj.newInstance();
        Obj o2 = Obj.newInstance();
        // call getEntries() to ensure that lazy init is done for o1
        // we don't do that for o2 to ensure that we compare with
        // o2.entries == null
        System.out.println("size: " + o1.getEntries().size());

        boolean equal = o1.equals(o2);
        assertThat(equal,equalTo(true));
    }
    @Test public void testLazyInitOfLists2() {
        Obj o1 = Obj.newInstance();
        Obj o2 = Obj.newInstance();
        // call getEntries() to ensure that lazy init is done for o2
        // we don't do that for o1 to ensure that we compare with
        // o1.entries == null
        System.out.println("size: " + o2.getEntries().size());

        boolean equal = o1.equals(o2);
        assertThat(equal,equalTo(true));
    }
}