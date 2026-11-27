/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmftest.getteronly;

import eu.mihosoft.vmftest.propertyinheritance.LocationX;
import eu.mihosoft.vmftest.propertyinheritance.LocationXY;
import eu.mihosoft.vmftest.propertyinheritance.LocationY;
import eu.mihosoft.vmftest.propertyinheritance.WithX;
import eu.mihosoft.vmftest.propertyinheritance.WithY;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ScalarGetterBuilderTest {
    @Test public void mutableBuilderCanImportAnImmutableGetterTraitThroughTheExistingSetter() {
        WithName source = ImmutableObj.newBuilder().withName("frozen").build();
        var target = MutableObj.newBuilder().withName("prior").build();
        var view = target.asReadOnly();
        var changes = new ArrayList<String>();
        target.vmf().changes().addListener(change -> changes.add(change.propertyName()), false);

        var staged = MutableObj.newBuilder().applyFrom(source);
        assertTrue(changes.isEmpty());
        assertEquals("prior", view.getName());
        assertEquals("frozen", staged.build().getName());
        staged.applyTo(target);
        assertEquals(List.of("name"), changes);
        assertEquals("frozen", target.getName());
        assertEquals("frozen", view.getName());
        MutableObj.newBuilder().applyFrom(target).applyTo(target);
        assertEquals(List.of("name"), changes);
        MutableObj.newBuilder().applyFrom(MutableObj.newInstance()).applyTo(target);
        assertEquals(List.of("name", "name"), changes);
        assertNull(target.getName());
        assertNull(view.getName());
        assertEquals("frozen", source.getName());
        assertFalse(Arrays.stream(WithName.class.getMethods()).anyMatch(method -> method.getName().equals("setName")));
    }

    @Test public void twoScalarGetterRolesImportOnlyTheirOwnStagedValues() {
        WithX x = LocationX.newBuilder().withX(12).build();
        WithY y = LocationY.newBuilder().withY(19).build();
        var target = LocationXY.newBuilder().withX(3).withY(4).build();
        var view = target.asReadOnly();
        var changes = new ArrayList<String>();
        target.vmf().changes().addListener(change -> changes.add(change.propertyName()), false);

        var staged = LocationXY.newBuilder().withY(8).applyFrom(x);
        var built = staged.build();
        assertEquals(12, built.getX(), 0.0);
        assertEquals(8, built.getY(), 0.0);
        staged.applyFrom(y);
        assertTrue(changes.isEmpty());
        staged.applyTo(target);
        assertEquals(12, view.getX(), 0.0);
        assertEquals(19, view.getY(), 0.0);
        assertEquals(List.of("x", "y"), changes);
    }
}
