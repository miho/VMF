package eu.mihosoft.vmftest.immutabletypes;

import java.lang.reflect.Method;

import org.junit.Assert;
import org.junit.Test;


public class ImmutableTypesTest {
    @Test
    public void immutableTypesTest() {

        boolean containsGetter = false;

        for (Method m : ImmutableType.class.getMethods()) {
            if(m.getName().startsWith("set")) {
                Assert.fail("Interface should not contain setter methods, but contains '" + m.getName()+"'");
            } else if ("getName".equals(m.getName())) {
                containsGetter = true;
            }
        }

        Assert.assertTrue("Interface must contain 'getName'. But it is missing", containsGetter);
    }
}