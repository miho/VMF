/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
package eu.mihosoft.vmf.runtime.core.internal;

import java.util.List;
import java.util.function.BiPredicate;

/** Structural comparison of model properties. Membership operations use target identity. */
public final class ReferenceContent {
    private ReferenceContent() {}

    public static boolean unorderedEquals(List<?> left, List<?> right, BiPredicate<Object,Object> equal) {
        int size = left == null ? 0 : left.size();
        if (size != (right == null ? 0 : right.size())) return false;
        if (left == right || size == 0) return true;
        // A pair of distinct-but-equal children needs two partners in the other graph.
        boolean[] matched = new boolean[size];
        for (Object child : left) {
            int i = 0;
            while (i < size && (matched[i] || !equal.test(child, right.get(i)))) i++;
            if (i == size) return false;
            matched[i] = true;
        }
        return true;
    }

    public static int hash(List<?> members, boolean ordered) {
        int hash = ordered ? 1 : 0;
        if (members != null) {
            for (Object member : members) {
                int memberHash = ((VObjectInternal) member)._vmf_hashCode();
                hash = ordered ? 31 * hash + memberHash : hash + memberHash;
            }
        }
        return hash;
    }
}
