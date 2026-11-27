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
package eu.mihosoft.vmftest.propertyinheritance;

import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Change;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class PropertyInheritanceTest {

    @Test
    public void propertyInheritanceTest01() {
        GCode1 gCode1 = GCode1.newInstance();
        Property p = gCode1.vmf().reflect().propertyByName("location").orElseThrow();
        // ensure the property uses the correct type
        Assert.assertEquals(LocationXY.type(), p.getType());
    }

    @Test
    public void inheritedPrimitiveDefaultsUseTheDeclaredTypeOnBothViews() {
        var point = LocationXY.newBuilder().withY(4).build();
        for (var reflection : List.of(point.vmf().reflect(), point.asReadOnly().vmf().reflect())) {
            var x = reflection.propertyByName("x").orElseThrow();
            var y = reflection.propertyByName("y").orElseThrow();
            Assert.assertEquals(Double.valueOf(0), x.getDefault());
            Assert.assertEquals(Double.valueOf(0), y.getDefault());
            Assert.assertFalse(x.isSet());
            Assert.assertTrue(y.isSet());
        }
    }

    @Test
    public void covariantLocationBranchesObserveOneLiveProperty() {
        var point = LocationXY.newBuilder().withX(3).withY(4).build();
        var code = GCode1.newBuilder().withLocation(point).build();
        WithLocationX left = code;
        WithLocationY right = code;
        WithLocation root = code;
        ReadOnlyWithLocationXY view = code.asReadOnly();
        Assert.assertSame(point, left.getLocation());
        Assert.assertSame(point, right.getLocation());
        Assert.assertSame(point, root.getLocation());
        Assert.assertSame(view.getLocation(), ((ReadOnlyWithLocationX) view).getLocation());
        Assert.assertSame(view.getLocation(), ((ReadOnlyWithLocationY) view).getLocation());
        Assert.assertSame(view.getLocation(), ((ReadOnlyWithLocation) view).getLocation());

        var reflection = code.vmf().reflect();
        Assert.assertEquals(1, reflection.properties().size());
        var field = reflection.propertyByName("location").orElseThrow();
        var changes = new ArrayList<Change>();
        var subscription = code.vmf().changes().addPropertyListener("location", changes::add);
        try {
            field.set(null);
            Assert.assertNull(left.getLocation());
            Assert.assertNull(right.getLocation());
            Assert.assertNull(root.getLocation());
            Assert.assertNull(view.getLocation());
            field.set(point);
        } finally {
            subscription.unsubscribe();
        }
        Assert.assertSame(point.asReadOnly(), view.getLocation());
        Assert.assertEquals(2, changes.size());
        Assert.assertTrue(changes.stream().allMatch(change -> change.object() == code
                && change.getType() == Change.ChangeType.PROPERTY));
    }
    
}
