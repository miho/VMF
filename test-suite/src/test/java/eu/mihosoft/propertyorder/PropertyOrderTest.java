package eu.mihosoft.propertyorder;

import eu.mihosoft.vmftest.propertyorder.CustomOrder;
import eu.mihosoft.vmftest.propertyorder.DefaultOrder;
import eu.mihosoft.vmftest.propertyorder.InheritedOrderSubClassWithBaseOrder;
import eu.mihosoft.vmftest.propertyorder.InheritedOrderSubClassWithoutBaseOrder;
import org.junit.Assert;
import org.junit.Test;

public class PropertyOrderTest {

    @Test
    public void propertyDefaultOrderTest() {

        // Uses default order (no custom property order specified)
        // -> currently, default order is alphabetically
        DefaultOrder defaultOrder = DefaultOrder.newInstance();

        // this is the order in which properties are supposed to be visited
        String[] propertiesD = new String[] {"b", "d", "x", "z"};

        // assert that the properties are visited in the correct order
        for(int i = 0; i < defaultOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesD[i], defaultOrder.vmf().reflect().properties().get(i).getName());
        }

    }

    @Test
    public void propertyCustomOrderTest() {

        // Uses custom order (defines custom property order)
        CustomOrder customOrder = CustomOrder.newInstance();

        // this is the order in which properties are supposed to be visited
        String[] propertiesC = new String[] {"z", "b", "d", "x"};

        // assert that the properties are visited in the correct order
        for(int i = 0; i < customOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesC[i], customOrder.vmf().reflect().properties().get(i).getName());
        }

    }

    @Test
    public void inheritedPropertyOrderTestWithoutBaseOrder() {

        // In this test we ensure that inherited properties work correctly.
        // It is valid for them to not define a custom order even if the properties of this
        // class do have a specified order.
        //
        // All inherited properties are visited prior to the properties of this class.
        InheritedOrderSubClassWithoutBaseOrder customOrder = InheritedOrderSubClassWithoutBaseOrder.newInstance();

        // Expected property order
        String[] propertiesC = new String[] {"baseA", "baseB", "baseZ", "a", "z", "b"};

        // assert that the properties are visited in the correct order
        for(int i = 0; i < customOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesC[i], customOrder.vmf().reflect().properties().get(i).getName());
        }

    }

    @Test
    public void inheritedPropertyOrderTestWithBaseOrder() {

        // In this test we ensure that inherited properties work correctly.
        // It is valid for them to define a custom order which has to be used
        // by the iterators of this class.
        //
        // All inherited properties are visited prior to the properties of this class.
        InheritedOrderSubClassWithBaseOrder customOrder = InheritedOrderSubClassWithBaseOrder.newInstance();

        // Expected property order
        String[] propertiesC = new String[] {"baseA", "baseZ", "baseB", "a", "z", "b"};

        // assert that the propperties are visited in the correct order
        for(int i = 0; i < customOrder.vmf().reflect().properties().size(); i++) {
            Assert.assertEquals(propertiesC[i], customOrder.vmf().reflect().properties().get(i).getName());
        }

    }

}
