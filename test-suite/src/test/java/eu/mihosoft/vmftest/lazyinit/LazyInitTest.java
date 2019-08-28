package eu.mihosoft.vmftest.lazyinit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

public class LazyInitTest {
    @Test public void testLazyInitOfLists() {
        Obj o1 = Obj.newInstance();
        Obj o2 = Obj.newInstance();
        System.out.println("size: "+ o1.getEntries().size());

        boolean equal = o1.equals(o2);
        assertThat(equal,equalTo(true));
    }
}