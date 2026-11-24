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
package eu.mihosoft.vmf.runtime.core;

/**
 * A reference set with model-defined positions. Indexed edits preserve identity
 * uniqueness. A move changes order while preserving the relationship.
 */
public interface OrderedRefSet<E extends VObject> extends RefSet<E> {
    E get(int index);

    /** Returns the position of a target or its read-only alias, or -1 if absent. */
    int indexOf(Object target);

    /** Inserts at a position. A target already present at any position is rejected. */
    void add(int index, E target);

    /** Replaces one target. A target present at a different position is rejected. */
    E set(int index, E target);

    E remove(int index);

    /** Moves a target to its position in the resulting collection. */
    void move(int fromIndex, int toIndex);
}
