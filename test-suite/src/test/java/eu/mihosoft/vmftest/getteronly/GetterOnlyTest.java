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

        // setting immutable property: expected to fail
        try {
            immutableObj.vmf().reflect().propertyByName("name").ifPresent(p -> p.set("new name"));
            Assert.fail("setting immutable property should not work");
        } catch ( Exception ex ) {
            ex.printStackTrace(System.err);
        }


        // setting mutable property: expected to work
        try {
            mutableObj.vmf().reflect().propertyByName("name").ifPresent(p -> p.set("new name"));
        } catch ( Exception ex ) {
            ex.printStackTrace(System.err);
            Assert.fail("setting mutable property must work");
        }
    }
}