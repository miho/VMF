/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.observableprop;

import eu.mihosoft.vmf.runtime.core.Property;
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

    @Test
    public void throwExceptionIfRuntimeMEthodsAreUsedForStaticReflection() {

        Property nameProperty = ObserveMyProperties.type().reflect().propertyByName("name").orElse(null);

        Assert.assertNotNull(nameProperty);

        try {
            nameProperty.get();
            Assert.fail("Cannot get property, property is not associated to an object");
        } catch(RuntimeException ex) {
            ex.printStackTrace(System.err);
        }

        try {
            nameProperty.set(null);
            Assert.fail("Cannot set property, property is not associated to an object");
        } catch(RuntimeException ex) {
            ex.printStackTrace(System.err);
        }

        try {
            nameProperty.getDefault();
            Assert.fail("Cannot get property default, property is not associated to an object");
        } catch(RuntimeException ex) {
            ex.printStackTrace(System.err);
        }

        try {
            nameProperty.setDefault(null);
            Assert.fail("Cannot set property default, property is not associated to an object");
        } catch(RuntimeException ex) {
            ex.printStackTrace(System.err);
        }

        try {
            nameProperty.isSet();
            Assert.fail("Cannot check if property is set, property is not associated to an object");
        } catch(RuntimeException ex) {
            ex.printStackTrace(System.err);
        }

        try {
            nameProperty.unset();
            Assert.fail("Cannot unset property, property is not associated to an object");
        } catch(RuntimeException ex) {
            ex.printStackTrace(System.err);
        }

        try {
            nameProperty.addChangeListener((c)->{});
            Assert.fail("Cannot register change listener,  property is not associated to an object");
        } catch(RuntimeException ex) {
            ex.printStackTrace(System.err);
        }
    }

}
