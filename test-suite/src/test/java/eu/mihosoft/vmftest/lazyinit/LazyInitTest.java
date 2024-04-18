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
package eu.mihosoft.vmftest.lazyinit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import org.junit.Test;

public class LazyInitTest {
    @Test public void testLazyInitOfLists1() {
        Obj o1 = Obj.newInstance();
        Obj o2 = Obj.newInstance();
        // call getEntries() to ensure that lazy init is done for o1
        // we don't do that for o2 to ensure that we compare with
        // o2.entries == null
        System.out.println("size: " + o1.getEntries().size());

        boolean equal = o1.equals(o2);
        assertThat(equal,equalTo(true));
    }
    @Test public void testLazyInitOfLists2() {
        Obj o1 = Obj.newInstance();
        Obj o2 = Obj.newInstance();
        // call getEntries() to ensure that lazy init is done for o2
        // we don't do that for o1 to ensure that we compare with
        // o1.entries == null
        System.out.println("size: " + o2.getEntries().size());

        boolean equal = o1.equals(o2);
        assertThat(equal,equalTo(true));
    }
}