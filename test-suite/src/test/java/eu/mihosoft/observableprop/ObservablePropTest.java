package eu.mihosoft.observableprop;

import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.ChangeListener;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.PropertyChange;
import eu.mihosoft.vmftest.observableprop.ObserveMyProperties;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ObservablePropTest {


    @Test
    public void observeSimplePropertyTest() {
        ObserveMyProperties observeMyProperties = ObserveMyProperties.newInstance();

        Property nameProperty = observeMyProperties.vmf().reflect().propertyByName("name").orElse(null);

        Assert.assertNotNull(nameProperty);

        List<String> expectedValues = Arrays.asList("ABC", "123", "", null);

        List<String> actualValues1 = new ArrayList<>();
        List<String> actualValues2 = new ArrayList<>();

        observeMyProperties.vmf().changes().addListener(
                (change) -> actualValues1.add((String) change.propertyChange().get().newValue())
        );

        nameProperty.addChangeListener(change ->
                actualValues2.add((String) change.propertyChange().get().newValue())
        );

        for (String expected : expectedValues) {
            observeMyProperties.setName(expected);
        }

        Assert.assertEquals(expectedValues, actualValues1);
        Assert.assertEquals(expectedValues, actualValues2);

    }

    @Test
    public void observeListPropertyTest() {
        ObserveMyProperties observeMyProperties = ObserveMyProperties.newInstance();

        Property values = observeMyProperties.vmf().reflect().propertyByName("values").orElse(null);

        Assert.assertNotNull(values);

        List<List<Integer>> actualValues1 = new ArrayList<>();
        List<List<Integer>> actualValues2 = new ArrayList<>();

        observeMyProperties.vmf().changes().addListener(
                (change) -> {
                    if (change.listChange().get().wasAdded()) {
                        actualValues1.add((List<Integer>) (Object) change.listChange().get().added().elements());
                    } else if (change.listChange().get().wasRemoved()) {
                        actualValues1.add((List<Integer>) (Object) change.listChange().get().removed().elements());
                    }
                }
        );

        values.addChangeListener(
                (change) -> {
                    if (change.listChange().get().wasAdded()) {
                        actualValues2.add((List<Integer>) (Object) change.listChange().get().added().elements());
                    } else if (change.listChange().get().wasRemoved()) {
                        actualValues2.add((List<Integer>) (Object) change.listChange().get().removed().elements());
                    }
                }
        );

        observeMyProperties.getValues().addAll(Arrays.asList(1, 2, 3));
        observeMyProperties.getValues().remove(2);
        observeMyProperties.getValues().removeAll(0, 1);

        // we made 3 modifications -> 3 changes
        Assert.assertEquals(3, actualValues1.size());
        Assert.assertEquals(3, actualValues2.size());

        // first, we added 3 elements
        Assert.assertEquals(3, actualValues1.get(0).size());
        Assert.assertEquals(3, actualValues2.get(0).size());

        // next, we removed 1 elements
        Assert.assertEquals(1, actualValues1.get(1).size());
        Assert.assertEquals(1, actualValues2.get(1).size());

        // finally, we removed 2 elements
        Assert.assertEquals(2, actualValues1.get(2).size());
        Assert.assertEquals(2, actualValues2.get(2).size());

    }

}
