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
import eu.mihosoft.vmf.runtime.core.Builder;
import eu.mihosoft.vmf.runtime.core.Immutable;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.internal.DeclaredClassNames;
import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;
import eu.mihosoft.vmf.runtime.core.VObject;
import eu.mihosoft.vmf.runtime.core.internal.ContainmentList;
import eu.mihosoft.vmf.runtime.core.internal.ObservableModelList;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Creation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Frame;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Operation;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.Value;
import eu.mihosoft.vmf.runtime.core.journal.ChangeJournal.ValueKind;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
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
            boolean reused = !usedInstances.add(entry.getValue());
            require(!reused || (entry.getValue() instanceof Immutable && entry.getValue() == original.get(entry.getKey())),
                    "Checkpoint copy reused a live object");
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

    private void allocate(Operation operation) {
        Set<String> immutableIds = new HashSet<>();
        collectImmutableIds(operation.before(), immutableIds);
        collectImmutableIds(operation.after(), immutableIds);
        for (Creation creation : operation.created()) {
            creation.properties().values().forEach(values -> collectImmutableIds(values, immutableIds));
        }
        Map<String, Creation> pending = new LinkedHashMap<>();
        for (Creation creation : operation.created()) {
            require(!objects.containsKey(creation.id()), "Reused creation ID " + creation.id());
            if (immutableIds.contains(creation.id())) {
                require(pending.putIfAbsent(creation.id(), creation) == null, "Duplicate creation ID " + creation.id());
            } else {
                VObject object = binding.create(creation.type(), creation.id());
                JournalSupport.mutable(object);
                register(creation, object);
            }
        }
        if (!pending.isEmpty()) buildImmutableTargets(pending);
    }

    private static void collectImmutableIds(List<Value> values, Set<String> ids) {
        for (Value value : values) if (value.kind() == ValueKind.IMMUTABLE_REFERENCE) ids.add(value.text());
    }

    // An immutable builder needs its referenced instances up front. Resolve the dependency
    // graph without relying on wire order, and reject a cycle before publishing the frame.
    private void buildImmutableTargets(Map<String, Creation> pending) {
        Map<String, Integer> remaining = new LinkedHashMap<>();
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        var ready = new java.util.ArrayDeque<Creation>();
        for (Creation creation : pending.values()) {
            Set<String> dependencies = new HashSet<>();
            creation.properties().values().forEach(values -> collectImmutableIds(values, dependencies));
            dependencies.retainAll(pending.keySet());
            if (dependencies.isEmpty()) ready.add(creation);
            else remaining.put(creation.id(), dependencies.size());
            for (String dependency : dependencies) {
                dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(creation.id());
            }
        }
        int built = 0;
        while (!ready.isEmpty()) {
            Creation creation = ready.removeFirst();
            // The binding selects an allowed model type and can assign an external ID
            // after construction. It must return the instance initialized from the wire.
            VObject[] initialized = new VObject[1];
            VObject result = binding.createImmutable(creation.type(), creation.id(), builder -> {
                require(initialized[0] == null, "Immutable initializer called twice");
                return initialized[0] = buildImmutable(creation, builder);
            });
            require(result != null && result == initialized[0], "Immutable factory did not return the initialized object");
            register(creation, result);
            built++;
            for (String id : dependents.getOrDefault(creation.id(), List.of())) {
                if (remaining.compute(id, (key, count) -> count - 1) == 0) ready.add(pending.get(id));
            }
        }
        require(built == pending.size(), "Cyclic immutable creation records");
    }

    private VObject buildImmutable(Creation creation, Builder builder) {
        VObject prototype = builder.build();
        require(prototype instanceof Immutable, "Expected an immutable builder for " + creation.type());
        List<Property> properties = ReflectImpl.readProperties(prototype.vmf().reflect());
        require(properties.size() == creation.properties().size() + creation.mapProperties().size()
                        + creation.typedMapProperties().size() + creation.setProperties().size(),
                "Incomplete immutable definition for " + creation.type());
        Map<String, Object> expected = new LinkedHashMap<>();
        for (Property property : properties) {
            String key = binding.propertyKey(prototype, property.getName());
            require(property.getName().equals(binding.propertyName(prototype, key)), "Invalid immutable property " + key);
            Class<?> element = propertyClass(prototype, property);
            Class<?> parameter = element;
            Object value;
            var type = property.getType();
            if (type.isMapType()) {
                if (mapKeyClass(prototype, property) == String.class) {
                    require(creation.mapProperties().containsKey(key), "Missing map property " + key);
                    value = decodeEntries(property, element, creation.mapProperties().get(key));
                } else {
                    require(creation.typedMapProperties().containsKey(key), "Missing typed map property " + key);
                    value = decodeTypedEntries(prototype, property, creation.typedMapProperties().get(key));
                }
                parameter = Map.class;
            } else if (type.isSetType()) {
                require(creation.setProperties().containsKey(key), "Missing set property " + key);
                value = decode(property, element, creation.setProperties().get(key));
                parameter = Collection.class;
            } else {
                var encoded = creation.properties().get(key);
                require(encoded != null, "Missing immutable property " + key);
                var decoded = decode(property, element, encoded);
                if (type.isListType() || type.isReferenceSetType()) {
                    value = decoded;
                    parameter = type.isReferenceSetType() ? Iterable.class : List.class;
                } else {
                    require(decoded.size() == 1, "Expected one value for immutable property " + key);
                    value = decoded.get(0);
                }
            }
            String name = property.getName();
            expected.put(name, value);
            String setter = "with" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            try {
                java.lang.reflect.Method method;
                try {
                    method = builder.getClass().getMethod(setter, parameter);
                } catch (NoSuchMethodException missing) {
                    // Checked scalar lists accept a Collection in their generated builder.
                    if (parameter != List.class) throw missing;
                    method = builder.getClass().getMethod(setter, Collection.class);
                }
                method.invoke(builder, value);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalArgumentException("Cannot initialize " + creation.type() + "." + name, ex);
            }
        }
        VObject result = builder.build();
        require(result != null && result.getClass() == prototype.getClass(), "Immutable builder changed its result type");
        for (Property property : ReflectImpl.readProperties(result.vmf().reflect())) {
            Object value = expected.get(property.getName());
            Object actual = property.get();
            boolean same;
            if (property.getType().isReferenceSetType()) {
                var members = new ArrayList<>();
                ((Iterable<?>) actual).forEach(members::add);
                same = matchesCollection(property, members, (List<?>) value);
            } else if (property.getType().isListType()) {
                same = sameList((List<?>) actual, (List<?>) value);
            } else if (property.getType().isSetType()) {
                same = actual.equals(new LinkedHashSet<>((List<?>) value));
            } else if (property.getType().isMapType()) {
                same = JournalSupport.sameMap((Map<?, ?>) actual, (Map<?, ?>) value);
            } else {
                same = sameValue(actual, value);
            }
            require(same, "Immutable builder produced a different value for " + property.getName());
        }
        return result;
    }

    private void register(Creation creation, VObject object) {
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
        if (object instanceof Immutable) return; // its builder initialized the complete definition
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
        require(!(target instanceof Immutable), "A journal operation cannot edit an immutable target");
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
        if (!operation.created().isEmpty()) allocate(operation);
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
            require(initial != null && initial.size() == 1 && initial.get(0).kind().isReference(),
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
        boolean scalarOpposite = ObservableModelList.hasScalarReciprocalOpposite(list);
        switch (operation.kind()) {
            case LIST_ADD:
                for (int i = 0; i < positions.size(); i++) {
                    int index = positions.get(i);
                    require(index <= expected.size(), "List add index out of bounds");
                    expected.add(index, after.get(i));
                }
                if (property.getMultiplicity().isUnique() || scalarOpposite
                        && normalizeReciprocalDuplicates(expected, after)) {
                    list.addAll(positions.stream().mapToInt(Integer::intValue).toArray(), after);
                } else if (!(list instanceof ContainmentList) || !ContainmentList.insertRecordedAliases(
                        list, positions.stream().mapToInt(Integer::intValue).toArray(), after)) {
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
                } else if (scalarOpposite || property.getMultiplicity().isUnique() || property.getKeyPropertyName().isPresent()) {
                    // Replacing positions separately can create a transient duplicate and normalize the wrong slot.
                    list.setAll(first, expected.subList(first, end));
                } else {
                    for (int i = 0; i < positions.size(); i++) list.set(positions.get(i), after.get(i));
                }
                if (scalarOpposite && !ObservableModelList.permutesIdentities(before, after)) {
                    normalizeReciprocalDuplicates(expected, after);
                }
                break;
            default: throw new IllegalArgumentException("Expected a list operation");
        }
        return expected;
    }

    private static boolean normalizeReciprocalDuplicates(List<Object> expected, List<Object> added) {
        // A scalar inverse removes the first occurrence whenever an edit adds its existing identity.
        var occurrences = new IdentityHashMap<Object, Integer>();
        for (Object value : added) {
            if (value != null) occurrences.put(value, 0);
        }
        if (occurrences.isEmpty()) return false;
        expected.forEach(value -> occurrences.computeIfPresent(value, (key, count) -> count + 1));
        return expected.removeIf(value -> {
            Integer count = occurrences.get(value);
            if (count == null || count <= 1) return false;
            occurrences.put(value, count - 1);
            return true;
        });
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
            return DeclaredClassNames.load(typeName, target.getClass().getClassLoader());
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
            case REFERENCE:
            case IMMUTABLE_REFERENCE:
                decoded = object(value.text());
                require((decoded instanceof Immutable) == (value.kind() == ValueKind.IMMUTABLE_REFERENCE),
                        "Reference has the wrong mutability for " + property.getName());
                break;
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
