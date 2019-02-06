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
    }
}