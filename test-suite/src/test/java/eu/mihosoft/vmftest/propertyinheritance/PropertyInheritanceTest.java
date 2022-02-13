package eu.mihosoft.vmftest.propertyinheritance;

import eu.mihosoft.vmf.runtime.core.Property;
import org.junit.Assert;
import org.junit.Test;

public class PropertyInheritanceTest {

    @Test
    public void propertyInheritanceTest01() {
        GCode1 gCode1 = GCode1.newInstance();
        Property p = gCode1.vmf().reflect().propertyByName("location").orElseThrow();
        // ensure the property uses the correct type
        Assert.assertEquals(LocationXY.type(), p.getType());
    }
    
}
