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

import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * The distinct targets of a model relationship. Membership uses target identity,
 * including the identity shared by a mutable object and its read-only view.
 * An ordinary reference set has no model-defined iteration order. Observe edits
 * through the owning property's change API. Two reference sets compare equal
 * when their targets have the same identities and their ordering contracts agree.
 * For ordered sets, corresponding targets must also have the same positions.
 * Read-only views expose the same membership and reject all edits.
 *
 * @param <E> the type of target exposed by this view
 */
public interface RefSet<E extends VObject> extends Iterable<E> {
    int size();
    default boolean isEmpty() { return size() == 0; }

    /** Tests membership using the underlying model object's identity. */
    boolean contains(Object target);

    /** Adds a non-null target. An existing target returns false without publishing an edit. */
    boolean add(E target);

    /** Adds distinct new targets after checking the incoming values. */
    boolean addAll(Iterable<? extends E> targets);

    /** Removes the specified target or its read-only alias. */
    boolean remove(Object target);

    void clear();

    /** Whether edits through this view are forbidden. */
    boolean isReadOnly();

    /** Returns a sequential stream of the targets exposed by this view. */
    default Stream<E> stream() { return StreamSupport.stream(spliterator(), false); }
}
