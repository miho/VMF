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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.mihosoft.vmf.runtime.core;


import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vcollections.VMapChangeEvent;
import eu.mihosoft.vcollections.VSetChangeEvent;

import java.util.*;
import java.util.function.Predicate;


/**
 * A live model change. For portable recording and replay, use
 * {@link eu.mihosoft.vmf.runtime.core.journal.JournalRecorder} and
 * {@link eu.mihosoft.vmf.runtime.core.journal.JournalReplayer}.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@SuppressWarnings("deprecation")
public interface Change {

    /**
     * Returns the object affected by this change
     * @return the object affected by this change
     */
    VObject object();

    /**
     * Returns the name of the property affected by this change.
     * @return the name of the property affected by this change
     */
    String propertyName();

    /**
     * Performs an undo operation (if possible).
     */
    void undo();

    /**
     * Applies this change to the specified affected object. VMF's native changes with
     * model-valued payloads require the original object returned by {@link #object()}.
     * For a change with scalar or external values, the caller can supply the corresponding
     * object in another compatible graph. This method does not resolve a target from a
     * graph root or check that the target is at the recorded checkpoint.
     * @param obj target object affected by this change
     * @throws IllegalArgumentException if a native change with a model-valued payload is
     * applied to a different object
     */
    void apply(VObject obj);

    /**
     * Indicates whether this change can be reverted.
     * @return {@code true} if this change can be reverted; {@code false} otherwise
     */
    boolean isUndoable();

    /**
     * Returns the property change (optional) which exists if this change affects a single property.
     * @return the property change (optional)
     */
    default Optional<PropertyChange> propertyChange() {
        return Optional.empty();
    }

    /**
     * Returns the list change (optional) which exists if this change affects a list (list elements added, removed, etc.).
     * @return the list change (optional)
     */
    default Optional<VListChangeEvent<Object>> listChange() {
        return Optional.empty();
    }

    /** Returns the affected entries before and after an edit to a managed map. */
    default Optional<VMapChangeEvent<Object, Object>> mapChange() {
        return Optional.empty();
    }

    /** Returns the members added to or removed from a managed set. */
    default Optional<VSetChangeEvent<Object>> setChange() {
        return Optional.empty();
    }

    /** An edit to an identity-unique model reference collection. */
    default Optional<RefSetChange> referenceSetChange() { return Optional.empty(); }

    /**
     * Change Type
     */
    enum ChangeType {
        /**
         * Change affects a single property.
         */
        PROPERTY,
        /**
         * Change affects a list.
         */
        LIST,
        /** Change affects entries in a managed map. */
        MAP,
        /** Change affects membership of a managed set. */
        SET,
        /** Change affects an unordered or ordered identity-unique model reference collection. */
        REFERENCE_SET
    }

    /**
     * Returns the type of this change.
     * @return the type of this change
     */
    ChangeType getType();

    /**
     * Returns the timestamp (in nano seconds) which denotes the creation of this change.
     * @return the timestamp (in nano seconds) which denotes the creation of this change
     */
    long getTimestamp();

}




