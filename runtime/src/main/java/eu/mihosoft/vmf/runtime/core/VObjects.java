/*
 * Copyright 2017-2023 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2023 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Just like {@link Objects}, this class consists of static utility methods for operating on VMF objects
 * (instances of {@link VObject}), or checking certain conditions before operation.
 */
public final class VObjects {

    private VObjects() {
        throw new AssertionError("Don't instantiate me!");
    }

    /**
     * Returns {@code true} if the arguments are equal to each other; {@code false} otherwise. If
     * the specified objects are model instances then the VMF {@code equals} method is used. If the specified
     * objects are collections which contain model instances then the VMF {@code equals} method is used element-wise.
     * @param o1 first object to compare
     * @param o2 second object to compare
     * @return {@code true} if the arguments are equal to each other; {@code false} otherwise.
     */
    @SuppressWarnings("deprecation")
    public static boolean equals(Object o1, Object o2) {
        if(o1 instanceof VObject && o2 instanceof VObject) {
            return ((VObjectInternal)o1)._vmf_equals(o2);
        } else if(o1 instanceof Collection && o2 instanceof Collection) {
            Collection l1 = (Collection) o1;
            Collection l2 = (Collection) o2;

            if(l1.size()!=l2.size()) {
                return false;
            }

            int n = l1.size();

            var l1It = l1.iterator();
            var l2It = l2.iterator();

            // compare element-wise
            for(int i = 0; i < n; i++) {

                Object e1 = l1It.next();
                Object e2 = l2It.next();

                if(!equals(e1,e2)) return false;
            }
        }

        return Objects.equals(o1, o2);
    }
}
