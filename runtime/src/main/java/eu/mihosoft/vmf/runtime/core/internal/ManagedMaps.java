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

import eu.mihosoft.vcollections.VMap;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Storage and copy operations used by generated string-keyed scalar map properties. */
public final class ManagedMaps {
    private ManagedMaps() {
        throw new AssertionError();
    }

    /** Creates a distinct, observable map that checks key and value types even for raw callers. */
    public static <V> VMap<String, V> create(Class<V> valueType) {
        return VMap.newInstance(new ScalarMap<>(valueType));
    }

    private static <V> Map<String, V> checkedCopy(Map<?, ?> source, Class<V> valueType) {
        Map<String, V> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(
                String.class.cast(Objects.requireNonNull(key, "managed map key")), valueType.cast(value)));
        return result;
    }

    /** Returns an immutable, independently owned default value for reflective access. */
    public static <V> Map<String, V> snapshot(Map<?, ?> source, Class<V> valueType) {
        return Collections.unmodifiableMap(checkedCopy(source, valueType));
    }

    /**
     * Copies entries while retaining the target map's identity and listeners. All entries are
     * validated before the first edit, including when source and target share a backing map.
     * Replacement preserves source iteration order. Appending retains existing key positions.
     */
    public static <V> void copyInto(VMap<String, V> target, Map<?, ?> source, Class<V> valueType, boolean append) {
        if (source == target) return;
        Map<String, V> entries = checkedCopy(source, valueType);
        if (!append) {
            // Keep the longest prefix of the requested keys already in that order.
            // Subsequent insertions append the remainder, including any keys that moved.
            var desired = entries.keySet().iterator();
            String next = desired.hasNext() ? desired.next() : null;
            for (String key : new ArrayList<>(target.keySet())) {
                if (key.equals(next)) {
                    next = desired.hasNext() ? desired.next() : null;
                } else {
                    target.remove(key);
                }
            }
        }
        entries.entrySet().removeIf(e -> target.containsKey(e.getKey())
                && Objects.equals(e.getValue(), target.get(e.getKey())));
        if (!entries.isEmpty()) target.putAll(entries);
    }

    private static final class ScalarMap<V> extends AbstractMap<String, V> {
        private final Class<V> valueType;
        private final Map<String, V> entries;

        ScalarMap(Class<V> valueType) {
            this.valueType = valueType;
            // Checked entries also guard assignment through Map.Entry.setValue().
            entries = Collections.checkedMap(new LinkedHashMap<>(), String.class, valueType);
        }

        @Override public Set<Entry<String, V>> entrySet() { return entries.entrySet(); }
        @Override public V get(Object key) { return entries.get(key); }
        @Override public boolean containsKey(Object key) { return entries.containsKey(key); }
        @Override public V remove(Object key) { return entries.remove(key); }
        @Override public int size() { return entries.size(); }
        @Override public void clear() { entries.clear(); }

        @Override
        public V put(String key, V value) {
            return entries.put(Objects.requireNonNull(key, "managed map key"), value);
        }

        @Override
        public void putAll(Map<? extends String, ? extends V> values) {
            entries.putAll(checkedCopy(values, valueType));
        }
    }
}
