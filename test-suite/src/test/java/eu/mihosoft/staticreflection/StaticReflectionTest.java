package eu.mihosoft.staticreflection;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmftest.staticreflection.Root;

import eu.mihosoft.vmftest.staticreflection.TypeA;
import eu.mihosoft.vmftest.staticreflection.TypeB;
import eu.mihosoft.vmftest.staticreflection.TypeC;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class StaticReflectionTest {
    @Test
    public void staticReflectionTest() {
        int propSize = Root.type().reflect().properties().size();

        Assert.assertEquals(1, propSize);

        Property p = Root.type().reflect().properties().get(0);

        try {
            p.set(null);
            Assert.fail(); // shouldn't be reached
        } catch(RuntimeException ex) {
            // exception expected
            ex.printStackTrace();
        }

        List<Type> superTypes = p.getType().superTypes();

        Assert.assertEquals(2, superTypes.size());

        List<String> typeNames = p.getType().superTypes().stream().map(t->t.getName()).collect(Collectors.toList());

        Assert.assertEquals(Arrays.asList(
                "eu.mihosoft.vmftest.staticreflection.TypeA", "eu.mihosoft.vmftest.staticreflection.TypeB"),
                typeNames
        );

        propSize = TypeA.type().reflect().properties().size();
        Assert.assertEquals(3,propSize);
        propSize = TypeB.type().reflect().properties().size();
        Assert.assertEquals(3,propSize);
        propSize = TypeC.type().reflect().properties().size();
        Assert.assertEquals(6,propSize);

    }
}
