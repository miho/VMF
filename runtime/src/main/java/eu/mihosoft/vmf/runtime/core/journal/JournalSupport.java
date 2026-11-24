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
package eu.mihosoft.vmf.runtime.core.journal;

import eu.mihosoft.vmf.runtime.core.Mutable;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternalModifiable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import static eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import static eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;

@SuppressWarnings("deprecation")
final class JournalSupport {
    private static final String STRING_LIST_TYPE = "eu.mihosoft.vcollections.VList<java.lang.String>";

    private JournalSupport() { }

    static String requireName(String value, String description) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + description);
        requireUnicode(value);
        return value;
    }

    private static void requireUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!Character.isSurrogate(character)) continue;
            if (!Character.isHighSurrogate(character) || i + 1 == value.length()
                    || !Character.isLowSurrogate(value.charAt(i + 1))) {
                throw new IllegalArgumentException("Text contains an unpaired surrogate");
            }
            i++;
        }
    }

    // These checks depend only on immutable String contents. Every graph scan still
    // reads every property and calls the binding and external codecs in their normal order.
    private static final class ValidatedText {
        private static final int CACHE_LIMIT = 4096;
        private static final int MAX_CACHED_LENGTH = 256;
        private static final int FIRST_CACHED_POSITION = 16;
        private static final int POSITION_LIMIT = 512;
        private final Map<String, Boolean> entries = new IdentityHashMap<>();
        private String[] listPositions;

        void name(String value, String description) {
            if (entries.get(value) != Boolean.TRUE) {
                requireName(value, description);
                remember(value, Boolean.TRUE);
            }
        }

        void unicode(String value) {
            if (!entries.containsKey(value)) {
                requireUnicode(value);
                remember(value, Boolean.FALSE);
            }
        }

        private void remember(String value, Boolean validName) {
            if (value.length() <= MAX_CACHED_LENGTH && entries.size() < CACHE_LIMIT) {
                entries.put(value, validName);
            }
        }

        void validateStringList(List<?> values, JournalBinding binding, GraphIndex objects) {
            int position = 0;
            for (Object value : values) {
                if (position >= FIRST_CACHED_POSITION && position < POSITION_LIMIT) {
                    String previous = listPositions == null ? null : listPositions[position];
                    // Position is only a lookup hint. The list is still read in iterator
                    // order, and an identical immutable String is the only cache hit.
                    if (previous == null || previous != value) {
                        validateGraphValue(value, binding, objects, this);
                        if (value instanceof String && ((String) value).length() <= MAX_CACHED_LENGTH) {
                            if (listPositions == null) listPositions = new String[POSITION_LIMIT];
                            listPositions[position] = (String) value;
                        }
                    }
                } else {
                    validateGraphValue(value, binding, objects, this);
                }
                position++;
            }
        }
    }

    static boolean isScalarType(Class<?> type) {
        return scalarKind(type) != null;
    }

    private static ValueKind scalarKind(Class<?> type) {
        if (type == String.class) return ValueKind.STRING;
        if (type == Integer.class) return ValueKind.INT;
        if (type == Double.class) return ValueKind.DOUBLE;
        if (type == Boolean.class) return ValueKind.BOOLEAN;
        if (type == Long.class) return ValueKind.LONG;
        if (type == Float.class) return ValueKind.FLOAT;
        if (type == Short.class) return ValueKind.SHORT;
        if (type == Byte.class) return ValueKind.BYTE;
        if (type == Character.class) return ValueKind.CHAR;
        return null;
    }

    static Value scalar(Object value, JournalBinding binding) {
        if (value == null) return new Value(ValueKind.NULL, null);
        ValueKind kind = scalarKind(value.getClass());
        return kind == null ? binding.valueCodecs().encode(value) : new Value(kind, scalarText(value));
    }

    private static String scalarText(Object value) {
        if (value instanceof Character) return Integer.toString((Character) value);
        if (value instanceof Float) return Float.toHexString((Float) value);
        if (value instanceof Double) return Double.toHexString((Double) value);
        return value.toString();
    }

    static void validateValue(ValueKind kind, String text) {
        if (kind == ValueKind.NULL) {
            if (text != null) throw new IllegalArgumentException("NULL value must have null text");
            return;
        }
        if (text == null) throw new IllegalArgumentException("Missing text for " + kind);
        if (kind == ValueKind.REFERENCE) {
            requireName(text, "reference ID");
        } else if (kind == ValueKind.STRING) {
            requireUnicode(text);
        } else if (!text.equals(scalarText(decodeScalar(kind, text)))) {
            throw new IllegalArgumentException("Noncanonical value for " + kind);
        }
    }

    static Object decodeScalar(ValueKind kind, String text) {
        switch (kind) {
            case NULL: return null;
            case STRING: return text;
            case BOOLEAN: return Boolean.valueOf(text);
            case BYTE: return Byte.valueOf(text);
            case SHORT: return Short.valueOf(text);
            case INT: return Integer.valueOf(text);
            case LONG: return Long.valueOf(text);
            case FLOAT: return Float.valueOf(text);
            case DOUBLE: return Double.valueOf(text);
            case CHAR:
                int value = Integer.parseInt(text);
                if (value < Character.MIN_VALUE || value > Character.MAX_VALUE) {
                    throw new IllegalArgumentException("Invalid character code unit");
                }
                return (char) value;
            default: throw new IllegalArgumentException("Expected a scalar value");
        }
    }

    static boolean isContainer(VObject object, String name) {
        if ("__vmf_prop_unnamed_container".equals(name)) return true;
        VObjectInternalModifiable internal = mutable(object);
        int id = internal._vmf_getPropertyIdByName(name);
        for (int parent : internal._vmf_getParentIndices()) {
            if (parent == id) return true;
        }
        return false;
    }

    static Property property(VObject object, String key, JournalBinding binding) {
        String name = requireName(binding.propertyName(object, key), "property name");
        if (isContainer(object, name) || !key.equals(binding.propertyKey(object, name))) {
            throw new IllegalArgumentException("Invalid journal property " + key);
        }
        return object.vmf().reflect().propertyByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown journal property " + key));
    }

    /** Session-local space for checking complete graphs without rebuilding the lookup storage. */
    static final class Workspace {
        private final ValidatedText text = new ValidatedText();
        private final Deque<GraphIndex> free = new ArrayDeque<>();

        GraphIndex index(VObject root, JournalBinding binding) {
            GraphIndex index = free.pollFirst();
            if (index == null) index = new GraphIndex(this);
            try {
                index.scan(root, binding);
                return index;
            } catch (RuntimeException | Error failure) {
                index.close();
                throw failure;
            }
        }

        Map<String, VObject> snapshot(VObject root, JournalBinding binding) {
            try (var index = index(root, binding)) {
                Map<String, VObject> snapshot = new LinkedHashMap<>();
                index.forEach(snapshot::put);
                return snapshot;
            }
        }

        void verifyState(VObject expected, VObject actual, JournalBinding binding) {
            try (var expectedObjects = index(expected, binding)) {
                verifyState(expectedObjects, actual, binding);
            }
        }

        void verifyState(GraphIndex expected, VObject actual, JournalBinding binding) {
            try (var actualObjects = index(actual, binding)) {
                compare(expected, actualObjects, binding);
            }
        }
    }

    static final class GraphIndex implements AutoCloseable {
        private final Workspace workspace;
        private final Map<String, IndexedObject> slots = new HashMap<>();
        private final List<IndexedObject> reachable = new ArrayList<>();
        private final Deque<VObjectInternalModifiable> pending = new ArrayDeque<>();

        private GraphIndex(Workspace workspace) {
            this.workspace = workspace;
        }

        private void scan(VObject root, JournalBinding binding) {
            pending.add(mutable(Objects.requireNonNull(root)));
            while (!pending.isEmpty()) {
                VObjectInternalModifiable object = pending.removeFirst();
                String id = binding.id(object);
                workspace.text.name(id, "object ID");
                workspace.text.name(binding.type(object), "object type");
                var slot = slots.get(id);
                if (slot == null) {
                    slot = new IndexedObject(id);
                    slots.put(id, slot);
                } else if (slot.object != null) {
                    throw new IllegalArgumentException("Duplicate object ID or containment entry " + id);
                }
                slot.object = object;
                reachable.add(slot);
                for (int child : object._vmf_getChildrenIndices()) {
                    Object value = object._vmf_getPropertyValueById(child);
                    if (value instanceof List<?>) {
                        for (Object entry : (List<?>) value) enqueueChild(object, entry);
                    } else {
                        enqueueChild(object, value);
                    }
                }
            }
            for (var slot : reachable) {
                for (Property property : ReflectImpl.readProperties(slot.object.vmf().reflect())) {
                    Object value = property.get();
                    var type = property.getType();
                    if (type.isMapType()) {
                        for (var entry : ((Map<?, ?>) value).entrySet()) {
                            validateGraphValue(entry.getKey(), binding, this, workspace.text);
                            validateGraphValue(entry.getValue(), binding, this, workspace.text);
                        }
                    } else if (type.isSetType()) {
                        for (Object member : (java.util.Set<?>) value) validateGraphValue(member, binding, this, workspace.text);
                    } else if (type.isReferenceSetType()) {
                        for (Object target : (Iterable<?>) value) validateGraphValue(target, binding, this, workspace.text);
                    } else if (type.isListType()) {
                        var values = (List<?>) value;
                        if (STRING_LIST_TYPE.equals(type.getName())) {
                            workspace.text.validateStringList(values, binding, this);
                        } else {
                            for (Object entry : values) validateGraphValue(entry, binding, this, workspace.text);
                        }
                    } else {
                        validateGraphValue(value, binding, this, workspace.text);
                    }
                }
            }
        }

        private void enqueueChild(VObject parent, Object child) {
            if (child == null) return;
            if (!(child instanceof VObject)) throw new IllegalArgumentException("Invalid containment relationship");
            var object = mutable((VObject) child);
            if (object._vmf_getContainer() != parent) {
                throw new IllegalArgumentException("Invalid containment relationship");
            }
            pending.add(object);
        }

        private VObject get(String id) {
            var slot = slots.get(id);
            return slot == null ? null : slot.object;
        }

        void forEach(BiConsumer<String, VObject> visitor) {
            for (var slot : reachable) visitor.accept(slot.id, slot.object);
        }

        @Override public void close() {
            // Hold no model object between scans. Retire unused keys when a graph
            // shrinks or a long-running recording has replaced much of its tree.
            if (slots.size() > 2L * reachable.size() + 64) {
                slots.values().removeIf(slot -> slot.object == null);
            }
            reachable.forEach(slot -> slot.object = null);
            reachable.clear();
            pending.clear();
            workspace.free.addFirst(this);
        }
    }

    private static final class IndexedObject {
        private final String id;
        private VObject object;

        private IndexedObject(String id) {
            this.id = id;
        }
    }

    private static void validateGraphValue(Object value, JournalBinding binding, GraphIndex objects,
                                           ValidatedText text) {
        if (value instanceof VObject) {
            VObject reference = (VObject) value;
            if (objects.get(binding.id(reference)) != reference) {
                throw new IllegalArgumentException("Model reference outside the checkpoint containment tree");
            }
        } else if (value instanceof String) {
            text.unicode((String) value);
        } else if (value != null && !isScalarType(value.getClass())) {
            binding.valueCodecs().encode(value);
        }
        // Built-in typed scalars produce canonical wire values by construction.
    }

    private static void compare(GraphIndex expected, GraphIndex actual, JournalBinding binding) {
        if (expected.reachable.size() != actual.reachable.size()) {
            throw new IllegalArgumentException("Replay produced a different set of object IDs");
        }
        for (var slot : expected.reachable) {
            if (actual.get(slot.id) == null) {
                throw new IllegalArgumentException("Replay produced a different set of object IDs");
            }
        }
        for (var slot : expected.reachable) {
            String id = slot.id;
            VObject object = slot.object;
            VObject other = actual.get(id);
            if (!binding.type(object).equals(binding.type(other))) {
                throw new IllegalArgumentException("Replay produced a different type for " + id);
            }
            int position = 0;
            for (Property property : ReflectImpl.readProperties(object.vmf().reflect())) {
                Property otherProperty = ReflectImpl.propertyAtOrNamed(
                        other.vmf().reflect(), position++, property.getName());
                Object value = property.get();
                Object otherValue = otherProperty.get();
                boolean same;
                if (property.getType().isMapType()) {
                    same = sameMap((Map<?, ?>) value, (Map<?, ?>) otherValue);
                } else if (property.getType().isReferenceSetType()) {
                    same = referenceIds(value, binding, property.getMultiplicity().isOrdered())
                            .equals(referenceIds(otherValue, binding, property.getMultiplicity().isOrdered()));
                } else if (property.getType().isListType()) {
                    List<?> left = (List<?>) value;
                    List<?> right = (List<?>) otherValue;
                    same = left.size() == right.size();
                    for (int index = 0; same && index < left.size(); index++) {
                        same = sameStateValue(left.get(index), right.get(index), binding);
                    }
                } else {
                    same = sameStateValue(value, otherValue, binding);
                }
                if (!same) throw new IllegalArgumentException("Replay differs at " + id + "." + property.getName());
            }
        }
    }

    private static java.util.Collection<String> referenceIds(Object members, JournalBinding binding, boolean ordered) {
        java.util.Collection<String> ids = ordered ? new ArrayList<>() : new java.util.HashSet<>();
        for (Object member : (Iterable<?>) members) ids.add(binding.id((VObject) member));
        return ids;
    }

    // The Java managed map exposes insertion order. Verify the order as well as the scalar
    // entries so a reordered native event stream cannot publish a divergent replay shadow.
    static boolean sameMap(Map<?, ?> expected, Map<?, ?> actual) {
        if (expected.size() != actual.size()) return false;
        var actualEntries = actual.entrySet().iterator();
        for (var entry : expected.entrySet()) {
            if (!entry.equals(actualEntries.next())) return false;
        }
        return true;
    }

    private static boolean sameStateValue(Object expected, Object actual, JournalBinding binding) {
        if (expected instanceof VObject) {
            return actual instanceof VObject && binding.id((VObject) expected).equals(binding.id((VObject) actual));
        }
        return Objects.equals(expected, actual);
    }

    static VObjectInternalModifiable mutable(VObject object) {
        if (!(object instanceof Mutable) || !(object instanceof VObjectInternalModifiable)) {
            throw new IllegalArgumentException("Expected a mutable generated VMF object");
        }
        return (VObjectInternalModifiable) object;
    }
}
