/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * a framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core.internal;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Key semantics shared by generated lookup methods and containment mutations. */
public final class KeyedEntries {
    private KeyedEntries() { throw new AssertionError(); }

    /** Finds an entry by scalar equality or by the identity of a referenced model object. */
    public static <E, K> Optional<E> find(List<E> entries, Function<? super E, ? extends K> keyOf, K key) {
        Objects.requireNonNull(key, "lookup key");
        E match = null;
        for (E entry : entries) {
            if (sameKey(keyOf.apply(entry), key)) {
                if (match != null) throw new IllegalStateException("Multiple entries have the requested key");
                match = entry;
            }
        }
        return Optional.ofNullable(match);
    }

    private static Object identity(Object key) {
        if (key instanceof VObjectInternal && ((VObjectInternal) key)._vmf_isReadOnly()) {
            return ((VObjectInternal) key)._vmf_getMutableObject();
        }
        return key;
    }

    private static boolean sameKey(Object first, Object second) {
        return first instanceof VObjectInternal ? identity(first) == identity(second) : Objects.equals(first, second);
    }

    /** Checks a setter on an entry only while that entry is attached to its keyed collection. */
    public static void requireKeyChange(VObjectInternal child, String property, Object next) {
        VObjectInternal parent = (VObjectInternal) child._vmf_getContainer();
        requireKeyChange(parent, child, property, next);
        ContainmentList.requirePendingKeyChange(parent, child, property, next);
    }

    static void requireKeyChange(VObjectInternal parent, VObjectInternal child, String property, Object next) {
        if (parent == null) return;
        for (int id : parent._vmf_getChildrenIndices()) {
            Object siblings = parent._vmf_getPropertyValueById(id);
            if (siblings instanceof ContainmentList) {
                ((ContainmentList<?>) siblings).requireKeyChange(child, property, next);
            }
        }
    }

    /** Replaces or appends keyed entries after validating the complete proposed edit. */
    public static <E> void copy(List<E> target, Collection<? extends E> incoming, boolean append) {
        var list = (ContainmentList<E>) target;
        if (incoming == target && !append) return;
        var entries = new java.util.ArrayList<E>(incoming);
        list.keys.validate(append ? Collections.emptyList() : list, entries);
        // Validate cycles and pending attachment paths before detaching a previous child.
        try (var check = list.attachingAll(entries)) { }
        if (!append) list.clear();
        list.addAll(entries);
    }

    static final class Rule<E> {
        private final String property;
        private final Function<? super E, ?> keyOf;
        private final List<E> entries;

        Rule(String property, Function<? super E, ?> keyOf, List<E> entries) {
            this.property = property;
            this.keyOf = keyOf;
            this.entries = entries;
        }

        private Object key(E entry) {
            if (entry == null) throw new IllegalArgumentException("A keyed entry must not be null");
            Object key = keyOf.apply(entry);
            if (key == null) throw new IllegalArgumentException("Missing entry key '" + property + "'");
            return key;
        }

        private IllegalArgumentException duplicate() {
            return new IllegalArgumentException("Duplicate entry key '" + property + "'");
        }

        void requireKeyChange(Object child, String changed, Object next) {
            if (!property.equals(changed) || entries.stream().noneMatch(e -> e == child)) return;
            if (next == null) throw new IllegalArgumentException("Missing entry key '" + property + "'");
            for (E entry : entries) {
                if (entry != child && sameKey(key(entry), next)) throw duplicate();
            }
        }

        void validate(Collection<?> outgoing, Collection<? extends E> incoming) {
            var excluded = Collections.newSetFromMap(new IdentityHashMap<>());
            excluded.addAll(outgoing);
            var scalars = new HashSet<>();
            var references = Collections.newSetFromMap(new IdentityHashMap<>());
            for (E entry : entries) {
                if (!excluded.contains(entry)) {
                    Object key = key(entry);
                    (key instanceof VObjectInternal ? references : scalars).add(identity(key));
                }
            }
            for (E entry : incoming) {
                Object key = key(entry);
                if (!(key instanceof VObjectInternal ? references : scalars).add(identity(key))) throw duplicate();
            }
        }
    }
}
