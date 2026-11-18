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
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternalModifiable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;
import static eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;

@SuppressWarnings("deprecation")
final class JournalSupport {
    private static final Map<Class<?>, ValueKind> SCALAR_KINDS = Map.of(
            String.class, ValueKind.STRING, Boolean.class, ValueKind.BOOLEAN,
            Byte.class, ValueKind.BYTE, Short.class, ValueKind.SHORT, Integer.class, ValueKind.INT,
            Long.class, ValueKind.LONG, Float.class, ValueKind.FLOAT, Double.class, ValueKind.DOUBLE,
            Character.class, ValueKind.CHAR);

    private JournalSupport() { }

    static String requireName(String value, String description) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + description);
        requireUnicode(value);
        return value;
    }

    private static void requireUnicode(String value) {
        if (value.codePoints().anyMatch(codePoint -> codePoint >= 0xd800 && codePoint <= 0xdfff)) {
            throw new IllegalArgumentException("Text contains an unpaired surrogate");
        }
    }

    static Value scalar(Object value) {
        if (value == null) return new Value(ValueKind.NULL, null);
        ValueKind kind = SCALAR_KINDS.get(value.getClass());
        if (kind == null) throw new IllegalArgumentException("Unsupported journal value type " + value.getClass().getName());
        return new Value(kind, scalarText(value));
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
        return Arrays.stream(internal._vmf_getParentIndices()).anyMatch(parent -> parent == id);
    }

    static Property property(VObject object, String key, JournalBinding binding) {
        String name = requireName(binding.propertyName(object, key), "property name");
        if (isContainer(object, name) || !key.equals(binding.propertyKey(object, name))) {
            throw new IllegalArgumentException("Invalid journal property " + key);
        }
        return object.vmf().reflect().propertyByName(name)
                .orElseThrow(() -> new IllegalArgumentException("Unknown journal property " + key));
    }

    /** Indexes the containment tree and verifies that all model references stay within it. */
    static Map<String, VObject> index(VObject root, JournalBinding binding) {
        Map<String, VObject> objects = new LinkedHashMap<>();
        Deque<VObject> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            VObject object = pending.removeFirst();
            VObjectInternalModifiable internal = mutable(object);
            String id = requireName(binding.id(object), "object ID");
            requireName(binding.type(object), "object type");
            if (objects.putIfAbsent(id, object) != null) {
                throw new IllegalArgumentException("Duplicate object ID or containment entry " + id);
            }
            for (int child : internal._vmf_getChildrenIndices()) {
                Object value = internal._vmf_getPropertyValueById(child);
                if (value instanceof List<?>) {
                    for (Object entry : (List<?>) value) enqueueChild(pending, object, entry);
                } else {
                    enqueueChild(pending, object, value);
                }
            }
        }
        for (VObject object : objects.values()) {
            for (Property property : object.vmf().reflect().properties()) {
                Object value = property.get();
                if (property.getType().isListType()) {
                    for (Object entry : (List<?>) value) validateGraphValue(entry, binding, objects);
                } else {
                    validateGraphValue(value, binding, objects);
                }
            }
        }
        return objects;
    }

    private static void enqueueChild(Deque<VObject> pending, VObject parent, Object child) {
        if (child == null) return;
        if (!(child instanceof VObject) || mutable((VObject) child)._vmf_getContainer() != parent) {
            throw new IllegalArgumentException("Invalid containment relationship");
        }
        pending.add((VObject) child);
    }

    private static void validateGraphValue(Object value, JournalBinding binding, Map<String, VObject> objects) {
        if (value instanceof VObject) {
            VObject reference = (VObject) value;
            if (objects.get(binding.id(reference)) != reference) {
                throw new IllegalArgumentException("Model reference outside the checkpoint containment tree");
            }
        } else {
            scalar(value);
        }
    }

    static void verifyState(VObject expected, VObject actual, JournalBinding binding) {
        Map<String, VObject> expectedObjects = index(expected, binding);
        Map<String, VObject> actualObjects = index(actual, binding);
        if (!expectedObjects.keySet().equals(actualObjects.keySet())) {
            throw new IllegalArgumentException("Replay produced a different set of object IDs");
        }
        expectedObjects.forEach((id, object) -> {
            VObject other = actualObjects.get(id);
            if (!binding.type(object).equals(binding.type(other))) {
                throw new IllegalArgumentException("Replay produced a different type for " + id);
            }
            for (Property property : object.vmf().reflect().properties()) {
                Property otherProperty = other.vmf().reflect().propertyByName(property.getName()).orElseThrow();
                Object value = property.get();
                Object otherValue = otherProperty.get();
                boolean same;
                if (property.getType().isListType()) {
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
        });
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
