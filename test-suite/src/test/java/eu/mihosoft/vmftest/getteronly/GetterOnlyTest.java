package eu.mihosoft.vmftest.getteronly;

import org.junit.Assert;
import org.junit.Test;

public class GetterOnlyTest {
    @Test
    public void getterOnlyTest() {

        ImmutableObj immutableObj = ImmutableObj.newBuilder().withName("immutable obj").build();
        MutableObj mutableObj = MutableObj.newBuilder().withName("mutable obj").build();
        
        WithName withName1 = immutableObj;
        WithName withName2 = mutableObj;

        Assert.assertTrue("immutable obj".equals(withName1.getName()));
        Assert.assertTrue("mutable obj".equals(withName2.getName()));

        // setting immutable property: expected to fail
        try {
            immutableObj.vmf().reflect().propertyByName("name").ifPresent(p -> p.set("new name"));
            Assert.fail("setting immutable property should not work");
        } catch ( Exception ex ) {
            ex.printStackTrace(System.err);
        }


        // setting mutable property: expected to work
        try {
            mutableObj.vmf().reflect().propertyByName("name").ifPresent(p -> p.set("new name"));
        } catch ( Exception ex ) {
            ex.printStackTrace(System.err);
            Assert.fail("setting mutable property must work");
        }
    }
}