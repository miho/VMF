package eu.mihosoft.ignoretostring;

import eu.mihosoft.vmftest.ignoretostring.SampleClass;
import org.junit.Assert;
import org.junit.Test;

public class ToStringTest {

    @Test
    public void testIgnoreTo() {
        SampleClass instance = SampleClass.newBuilder().withName("my name").withIgnoredProp("ignored prop").build();

        String toString = instance.toString();

        Assert.assertTrue("'my name prop' must be present", toString.contains("my name"));
        Assert.assertTrue("'ignored prop' must not be present", !toString.contains("ignored prop"));
    }

}
