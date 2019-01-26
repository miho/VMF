package eu.mihosoft.vmftest.defaultvaluesandbuilders;

import org.junit.Assert;
import org.junit.Test;


public class DefaultValuesAndBuildersTest {
    @Test
    public void defaultValuesAndBuildersTest() {

        // we use the builder instead of newInstance
        WithDefaultValues withValuesInstance = WithDefaultValues.newInstance();
        WithDefaultValues withValuesBuilder = WithDefaultValues.newBuilder().build();

        // and now we expect the default values to be set (just like with `newInstance()` )
        Assert.assertEquals("my name", withValuesInstance.getName());
        Assert.assertEquals(true, withValuesInstance.isVisible());

        Assert.assertEquals("my name", withValuesBuilder.getName());
        Assert.assertEquals(true, withValuesBuilder.isVisible());
    }
}