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

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Creation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Forward replay of a journal against an independent checkpoint. */
public final class JournalReplayer {
    private static final Map<String, Class<?>> PRIMITIVES = Map.of(
            "boolean", boolean.class, "byte", byte.class, "short", short.class,
            "int", int.class, "long", long.class, "float", float.class,
            "double", double.class, "char", char.class);
    private static final Map<Class<?>, Class<?>> BOXES = Map.of(
            boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class,
            int.class, Integer.class, long.class, Long.class, float.class, Float.class,
            double.class, Double.class, char.class, Character.class);

    private final JournalBinding binding;
    private final JournalSupport.Workspace workspace;
    private final Map<String, VObject> objects;
    private final Set<VObject> usedInstances = Collections.newSetFromMap(new IdentityHashMap<>());

    JournalReplayer(JournalBinding binding, Map<String, VObject> original, Map<String, VObject> objects,
                    JournalSupport.Workspace workspace) {
        this.binding = binding;
        this.workspace = workspace;
        this.objects = objects;
        require(original.keySet().equals(objects.keySet()), "Checkpoint copy changed its IDs");
        usedInstances.addAll(original.values());
        for (Map.Entry<String, VObject> entry : objects.entrySet()) {
            require(usedInstances.add(entry.getValue()), "Checkpoint copy reused a live object");
            require(binding.type(entry.getValue()).equals(binding.type(original.get(entry.getKey()))),
                    "Checkpoint copy changed an object type");
        }
    }

    /**
     * Returns a replayed copy. The caller supplies the identifier of the loaded checkpoint
     * (for example, a digest of its stored bytes). The input graph remains available on failure.
     */
    @SuppressWarnings("unchecked")
    public static <T extends VObject> T replay(T checkpointRoot, JournalBinding binding,
                                               String checkpoint, ChangeJournal journal) {
        require(journal.checkpoint().equals(checkpoint), "Journal checkpoint does not match");
        require(journal.model().equals(binding.modelId()) && journal.modelVersion() == binding.modelVersion(),
                "Journal model version does not match");
        JournalSupport.Workspace workspace = new JournalSupport.Workspace();
        Map<String, VObject> original = workspace.snapshot(checkpointRoot, binding);
        require(original.get(journal.root()) == checkpointRoot, "Journal root does not match");
        T copy = (T) checkpointRoot.clone();
        JournalReplayer replay = new JournalReplayer(binding, original, workspace.snapshot(copy, binding), workspace);
        for (Frame frame : journal.transactions()) replay.applyFrame(frame, copy);
        return copy;
    }

    void excludeLiveInstance(VObject object) {
        require(usedInstances.add(object), "Replay factory reused a live object");
    }

    void applyFrame(Frame frame, VObject root) {
        for (Operation operation : frame.operations()) apply(operation);
        verifyGraph(root);
    }

    private void verifyGraph(VObject root) {
        try (var reachable = workspace.index(root, binding)) {
            reachable.forEach((id, object) -> {
                if (objects.get(id) != object) {
                    throw new IllegalArgumentException("Unregistered or changed object ID " + id);
                }
            });
        }
    }

    private void allocate(Creation creation) {
        require(!objects.containsKey(creation.id()), "Reused creation ID " + creation.id());
        VObject object = binding.create(creation.type(), creation.id());
        JournalSupport.mutable(object);
        require(usedInstances.add(object), "Object factory reused an instance for " + creation.id());
        require(creation.id().equals(binding.id(object)) && creation.type().equals(binding.type(object)),
                "Object factory returned the wrong ID or type for " + creation.id());
        objects.put(creation.id(), object);
    }

    private VObject object(String id) {
        VObject object = objects.get(id);
        require(object != null && id.equals(binding.id(object)), "Unknown or changed object ID " + id);
        return object;
    }

    private void initialize(Creation creation, boolean modelProperties) {
        VObject object = object(creation.id());
        creation.properties().forEach((key, values) -> {
            Property property = JournalSupport.property(object, key, binding);
            require(!property.getType().isMapType(), "Map property requires keyed entries for " + key);
            require(!property.getType().isSetType(), "Set property requires set members for " + key);
            if (property.getType().isModelType() != modelProperties) return;
            List<Object> decoded = decode(property, propertyClass(object, property), values);
            if (property.getType().isListType() || property.getType().isReferenceSetType()) {
                VList<Object> list = list(object, property);
                if (!matchesCollection(property, list, decoded)) {
                    if (property.getMultiplicity().isUnique()) {
                        property.set(decoded);
                    } else {
                        list.clear();
                        list.addAll(decoded);
                    }
                }
                require(matchesCollection(property, list, decoded), "Failed to initialize " + key);
            } else {
                require(decoded.size() == 1, "Creation scalar needs one value for " + key);
                if (!sameValue(property.get(), decoded.get(0))) property.set(decoded.get(0));
                require(sameValue(property.get(), decoded.get(0)), "Failed to initialize " + key);
            }
        });
        if (!modelProperties) {
            creation.mapProperties().forEach((key, entries) -> {
                Property property = JournalSupport.property(object, key, binding);
                require(property.getType().isMapType(), "Expected a managed map for " + key);
                require(mapKeyClass(object, property) == String.class, "Expected a String-keyed map for " + key);
                var decoded = decodeEntries(property, propertyClass(object, property), entries);
                requireCapacity(property, decoded.size());
                var map = map(property);
                if (!JournalSupport.sameMap(decoded, map)) {
                    map.clear();
                    map.putAll(decoded);
                }
                require(JournalSupport.sameMap(decoded, map), "Failed to initialize map " + key);
            });
            creation.typedMapProperties().forEach((key, entries) -> {
                Property property = JournalSupport.property(object, key, binding);
                require(property.getType().isMapType() && mapKeyClass(object, property) != String.class,
                        "Expected a non-String-keyed managed map for " + key);
                var decoded = decodeTypedEntries(object, property, entries);
                requireCapacity(property, decoded.size());
                var map = map(property);
                if (!JournalSupport.sameMap(decoded, map)) {
                    map.clear();
                    map.putAll(decoded);
                }
                require(JournalSupport.sameMap(decoded, map), "Failed to initialize map " + key);
            });
            creation.setProperties().forEach((key, values) -> {
                Property property = JournalSupport.property(object, key, binding);
                require(property.getType().isSetType(), "Expected a managed set for " + key);
                var members = new LinkedHashSet<>(decode(property, propertyClass(object, property), values));
                var set = set(property);
                set.replaceContents(members);
                require(set.equals(members), "Failed to initialize set " + key);
            });
        }
    }

    private void apply(Operation operation) {
        VObject target = object(operation.target());
        Property property = JournalSupport.property(target, operation.property(), binding);
        if (property.getType().isMapType()) {
            if (mapKeyClass(target, property) == String.class) {
                require(operation.kind() == ChangeJournal.Kind.MAP_EDIT, "Expected a String map edit for " + property.getName());
                var type = propertyClass(target, property);
                applyMap(property, decodeEntries(property, type, operation.beforeEntries()),
                        decodeEntries(property, type, operation.afterEntries()));
            } else {
                require(operation.kind() == ChangeJournal.Kind.TYPED_MAP_EDIT, "Expected a typed map edit for " + property.getName());
                applyMap(property, decodeTypedEntries(target, property, operation.beforeTypedEntries()),
                        decodeTypedEntries(target, property, operation.afterTypedEntries()));
            }
            return;
        }
        if (property.getType().isSetType()) {
            require(operation.kind() == ChangeJournal.Kind.SET_EDIT, "Expected a set edit for " + property.getName());
            var type = propertyClass(target, property);
            var before = decode(property, type, operation.before());
            var after = decode(property, type, operation.after());
            var set = set(property);
            require(set.containsAll(before) && Collections.disjoint(set, after),
                    "Set membership precondition differs for " + property.getName());
            var expected = new LinkedHashSet<>(set);
            expected.removeAll(before);
            expected.addAll(after);
            set.replaceContents(expected);
            require(set.equals(expected), "Set result differs for " + property.getName());
            return;
        }
        require(operation.kind() != ChangeJournal.Kind.MAP_EDIT && operation.kind() != ChangeJournal.Kind.TYPED_MAP_EDIT
                && operation.kind() != ChangeJournal.Kind.SET_EDIT, "Collection edit has the wrong property " + property.getName());
        operation.created().forEach(this::allocate);
        Class<?> type = propertyClass(target, property);
        List<Object> before = decode(property, type, operation.before());
        List<Object> after = decode(property, type, operation.after());
        operation.created().forEach(creation -> initialize(creation, false));
        initializeEntryKeys(property, operation);
        Object expected;
        boolean references = property.getType().isReferenceSetType();
        boolean many = property.getType().isListType() || references;
        if (many) {
            var list = list(target, property);
            expected = references && !property.getMultiplicity().isOrdered()
                    ? applyReferenceMembership(property, list, operation, before, after)
                    : applyList(list, property, operation, before, after);
        } else {
            require(operation.kind() == ChangeJournal.Kind.SET, "Expected a scalar operation");
            require(sameValue(property.get(), before.get(0)), "Old value differs for " + operation.property());
            expected = after.get(0);
            property.set(expected);
        }
        // The edit can already wire an opposite on a new object. Initialization
        // keeps that relation if it matches the recorded initial value.
        operation.created().forEach(creation -> initialize(creation, true));
        if (many) {
            require(matchesCollection(property, list(target, property), (List<?>) expected), "Collection result differs for " + operation.property());
        } else {
            require(sameValue(property.get(), expected), "Value result differs for " + operation.property());
        }
    }

    private void initializeEntryKeys(Property edited, Operation operation) {
        if (operation.created().isEmpty()) return;
        Map<String, Creation> created = new LinkedHashMap<>();
        for (Creation creation : operation.created()) created.put(creation.id(), creation);
        initializeEntryKeys(edited, operation.after(), created);
        for (Creation owner : operation.created()) {
            owner.properties().forEach((key, values) ->
                    initializeEntryKeys(JournalSupport.property(object(owner.id()), key, binding), values, created));
        }
    }

    private void initializeEntryKeys(Property list, List<Value> values, Map<String, Creation> created) {
        var keyName = list.getKeyPropertyName();
        if (keyName.isEmpty()) return;
        for (Value value : values) {
            if (value.kind() != ValueKind.REFERENCE) continue;
            Creation record = created.get(value.text());
            if (record == null) continue;
            var entry = object(record.id());
            var key = entry.vmf().reflect().propertyByName(keyName.get()).orElseThrow();
            if (!key.getType().isModelType()) continue; // scalar keys are already initialized
            var initial = record.properties().get(binding.propertyKey(entry, key.getName()));
            require(initial != null && initial.size() == 1 && initial.get(0).kind() == ValueKind.REFERENCE,
                    "Missing model entry key " + key.getName());
            Object decoded = decodeValue(key, propertyClass(entry, key), initial.get(0));
            if (key.get() != decoded) key.set(decoded);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(Property property) {
        return (Map<Object, Object>) property.get();
    }

    @SuppressWarnings("unchecked")
    private static eu.mihosoft.vcollections.VSet<Object> set(Property property) {
        return (eu.mihosoft.vcollections.VSet<Object>) property.get();
    }

    private void applyMap(Property property, Map<?, Object> before, Map<?, Object> after) {
        var map = map(property);
        // Verify the entire edit before touching the map, including absence for new keys.
        before.forEach((key, value) -> require(map.containsKey(key) && Objects.equals(map.get(key), value),
                "Map precondition differs for " + property.getName() + "[" + key + "]"));
        after.keySet().forEach(key -> require(before.containsKey(key) || !map.containsKey(key),
                "Map key already exists for " + property.getName() + "[" + key + "]"));

        Map<Object, Object> expected = new LinkedHashMap<>(map);
        for (Object key : before.keySet()) if (!after.containsKey(key)) expected.remove(key);
        expected.putAll(after);
        requireCapacity(property, expected.size());
        for (Object key : before.keySet()) if (!after.containsKey(key)) map.remove(key);
        map.putAll(after);
        require(JournalSupport.sameMap(expected, map), "Map result differs for " + property.getName());
    }

    private static void requireCapacity(Property property, int size) {
        int upper = property.getMultiplicity().getUpper();
        require(upper < 0 || size <= upper, "Map upper bound exceeded for " + property.getName());
    }

    private Map<String, Object> decodeEntries(Property property, Class<?> type, Map<String, Value> entries) {
        Map<String, Object> decoded = new LinkedHashMap<>();
        entries.forEach((key, value) -> decoded.put(key, decodeValue(property, type, value)));
        return decoded;
    }

    private Map<Object, Object> decodeTypedEntries(VObject target, Property property, Map<Value, Value> entries) {
        var keyClass = mapKeyClass(target, property);
        var valueClass = propertyClass(target, property);
        Map<Object, Object> decoded = new LinkedHashMap<>();
        entries.forEach((key, value) -> decoded.put(decodeValue(property, keyClass, key),
                decodeValue(property, valueClass, value)));
        return decoded;
    }

    private List<Object> applyReferenceMembership(Property property, VList<Object> list, Operation operation, List<Object> before, List<Object> after) {
        require(operation.kind() == ChangeJournal.Kind.REF_SET_EDIT, "Expected a reference membership edit");
        Set<Object> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        removed.addAll(before);
        Set<Object> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(list);
        require(present.containsAll(before) && after.stream().noneMatch(present::contains),
                "Reference membership precondition differs for " + operation.property());
        List<Object> expected = new ArrayList<>(list.size() - before.size() + after.size());
        for (Object value : list) if (!removed.contains(value)) expected.add(value);
        expected.addAll(after);
        property.set(expected);
        return expected;
    }

    private List<Object> applyList(VList<Object> list, Property property, Operation operation, List<Object> before, List<Object> after) {
        List<Object> expected = new ArrayList<>(list);
        List<Integer> positions = operation.indices();
        switch (operation.kind()) {
            case LIST_ADD:
                for (int i = 0; i < positions.size(); i++) {
                    int index = positions.get(i);
                    require(index <= expected.size(), "List add index out of bounds");
                    expected.add(index, after.get(i));
                }
                if (property.getMultiplicity().isUnique()) {
                    list.addAll(positions.stream().mapToInt(Integer::intValue).toArray(), after);
                } else {
                    for (int i = 0; i < positions.size(); i++) list.add(positions.get(i), after.get(i));
                }
                break;
            case LIST_REMOVE:
                checkOldListValues(list, positions, before);
                for (int i = positions.size() - 1; i >= 0; i--) {
                    int index = positions.get(i);
                    expected.remove(index);
                    list.remove(index);
                }
                break;
            case LIST_SET:
                checkOldListValues(list, positions, before);
                for (int i = 0; i < positions.size(); i++) expected.set(positions.get(i), after.get(i));
                int first = positions.get(0);
                int end = positions.get(positions.size() - 1) + 1;
                if (end - first == positions.size()) {
                    list.setAll(first, after);
                } else if (property.getMultiplicity().isUnique() || property.getKeyPropertyName().isPresent()) {
                    // An in-place permutation can transiently duplicate a member/key if applied position by position.
                    list.setAll(first, expected.subList(first, end));
                } else {
                    for (int i = 0; i < positions.size(); i++) list.set(positions.get(i), after.get(i));
                }
                break;
            default: throw new IllegalArgumentException("Expected a list operation");
        }
        return expected;
    }

    private static void checkOldListValues(List<?> list, List<Integer> positions, List<Object> before) {
        for (int i = 0; i < positions.size(); i++) {
            int index = positions.get(i);
            require(index < list.size() && sameValue(list.get(index), before.get(i)),
                    "List precondition differs at position " + index);
        }
    }

    @SuppressWarnings("unchecked")
    private static VList<Object> list(VObject owner, Property property) {
        if (property.getType().isReferenceSetType()) {
            var internal = (eu.mihosoft.vmf.runtime.core.internal.VObjectInternal) owner;
            return (VList<Object>) internal._vmf_getPropertyValueById(internal._vmf_getPropertyIdByName(property.getName()));
        }
        return (VList<Object>) property.get();
    }

    private static boolean matchesCollection(Property property, List<?> actual, List<?> expected) {
        if (property.getType().isReferenceSetType() && !property.getMultiplicity().isOrdered()) {
            if (actual.size() != expected.size()) return false;
            Set<Object> members = Collections.newSetFromMap(new IdentityHashMap<>());
            members.addAll(actual);
            for (Object value : expected) if (!members.remove(value)) return false;
            return members.isEmpty();
        }
        return sameList(actual, expected);
    }

    private static Class<?> propertyClass(VObject target, Property property) {
        var declared = property.getType();
        String typeName = declared.isMapType() ? declared.getDescriptor().getArguments().get(1).getName()
                : declared.isSetType() ? declared.getDescriptor().getArguments().get(0).getName()
                : declared.getElementTypeName().orElse(declared.getName());
        return resolveClass(target, typeName);
    }

    private static Class<?> mapKeyClass(VObject target, Property property) {
        return resolveClass(target, property.getType().getDescriptor().getArguments().get(0).getName());
    }

    private static Class<?> resolveClass(VObject target, String typeName) {
        Class<?> type = PRIMITIVES.get(typeName);
        if (type != null) return type;
        try {
            return Class.forName(typeName, false, target.getClass().getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new IllegalArgumentException("Cannot resolve property type " + typeName, ex);
        }
    }

    private List<Object> decode(Property property, Class<?> type, List<Value> values) {
        if (values.isEmpty()) return List.of();
        List<Object> result = new ArrayList<>(values.size());
        for (Value value : values) result.add(decodeValue(property, type, value));
        return result;
    }

    private Object decodeValue(Property property, Class<?> type, Value value) {
        Object decoded;
        switch (value.kind()) {
            case REFERENCE: decoded = object(value.text()); break;
            case EXTERNAL: decoded = binding.valueCodecs().decode(value); break;
            default: decoded = JournalSupport.decodeScalar(value.kind(), value.text());
        }
        if (decoded == null ? type.isPrimitive() : !BOXES.getOrDefault(type, type).isInstance(decoded)) {
            throw new IllegalArgumentException("Journal value has the wrong type for " + property.getName());
        }
        return decoded;
    }

    private static boolean sameList(List<?> left, List<?> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!sameValue(left.get(i), right.get(i))) return false;
        }
        return true;
    }

    private static boolean sameValue(Object left, Object right) {
        if (left instanceof VObject || right instanceof VObject) return left == right;
        return Objects.equals(left, right);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
