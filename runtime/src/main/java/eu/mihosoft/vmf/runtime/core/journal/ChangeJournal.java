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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, model-independent records of forward changes from a checkpoint. */
public final class ChangeJournal {
    public static final String FORMAT = "vmf-journal";
    public static final int VERSION = 1;
    public static final int EXTERNAL_VALUES_VERSION = 2;
    public static final int MAPS_VERSION = 3;

    private final String model;
    private final int modelVersion;
    private final String root;
    private final String checkpoint;
    private final long baseSequence;
    private final List<Frame> transactions;

    public ChangeJournal(String model, int modelVersion, String root, String checkpoint,
                         long baseSequence, List<Frame> transactions) {
        this.model = JournalSupport.requireName(model, "model");
        this.root = JournalSupport.requireName(root, "root ID");
        this.checkpoint = JournalSupport.requireName(checkpoint, "checkpoint");
        if (modelVersion < 1 || baseSequence < 0) {
            throw new IllegalArgumentException("Invalid model version or base sequence");
        }
        this.modelVersion = modelVersion;
        this.baseSequence = baseSequence;
        this.transactions = List.copyOf(transactions);
        long sequence = baseSequence;
        for (Frame frame : this.transactions) {
            if (sequence == Long.MAX_VALUE || frame.sequence() != sequence + 1) {
                throw new IllegalArgumentException("Journal sequence must be consecutive");
            }
            sequence = frame.sequence();
        }
    }

    public String model() { return model; }
    public int modelVersion() { return modelVersion; }
    public String root() { return root; }
    public String checkpoint() { return checkpoint; }
    public long baseSequence() { return baseSequence; }
    public List<Frame> transactions() { return transactions; }

    /** Returns the oldest wire version that can represent these records. */
    public int wireVersion() {
        int version = VERSION;
        for (Frame frame : transactions) {
            for (Operation operation : frame.operations()) {
                if (operation.kind() == Kind.MAP_EDIT) return MAPS_VERSION;
                if (hasExternalValues(operation.before()) || hasExternalValues(operation.after())) {
                    version = EXTERNAL_VALUES_VERSION;
                }
                for (Creation creation : operation.created()) {
                    if (!creation.mapProperties().isEmpty()) return MAPS_VERSION;
                    if (creation.properties().values().stream().anyMatch(ChangeJournal::hasExternalValues)) {
                        version = EXTERNAL_VALUES_VERSION;
                    }
                }
            }
        }
        return version;
    }

    private static boolean hasExternalValues(List<Value> values) {
        return values.stream().anyMatch(value -> value.kind() == ValueKind.EXTERNAL);
    }

    public enum Kind { SET, LIST_ADD, LIST_REMOVE, LIST_SET, MAP_EDIT }

    public enum ValueKind { NULL, REFERENCE, STRING, BOOLEAN, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, CHAR, EXTERNAL }

    /** A canonical scalar, a persistent model-object reference, or a registered external value. */
    public static final class Value {
        private final ValueKind kind;
        private final String text;
        private final String codecId;

        public Value(ValueKind kind, String text) {
            this.kind = Objects.requireNonNull(kind, "value kind");
            if (kind == ValueKind.EXTERNAL) throw new IllegalArgumentException("External values need a codec ID");
            JournalSupport.validateValue(kind, text);
            this.text = text;
            this.codecId = null;
        }

        private Value(String codecId, String text) {
            this.kind = ValueKind.EXTERNAL;
            this.codecId = JournalSupport.requireName(codecId, "external codec ID");
            JournalSupport.validateValue(ValueKind.STRING, text);
            this.text = text;
        }

        /** Creates an external value in the registered codec's canonical representation. */
        public static Value external(String codecId, String text) {
            return new Value(codecId, text);
        }

        public ValueKind kind() { return kind; }
        public String text() { return text; }
        /** Returns the wire codec ID for an external value, or null for other values. */
        public String codecId() { return codecId; }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Value)) return false;
            Value value = (Value) other;
            return kind == value.kind && Objects.equals(text, value.text) && Objects.equals(codecId, value.codecId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, text, codecId);
        }
    }

    private static Map<String, Value> copyEntries(Map<String, Value> entries) {
        if (entries.isEmpty()) return Map.of();
        Map<String, Value> copy = new LinkedHashMap<>();
        entries.forEach((key, value) -> {
            JournalSupport.validateValue(ValueKind.STRING, key);
            Objects.requireNonNull(value, "map entry value");
            if (value.kind() == ValueKind.REFERENCE || value.kind() == ValueKind.EXTERNAL) {
                throw new IllegalArgumentException("Managed maps require built-in scalar values");
            }
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    /** Initial properties of an object, with a singleton value for each scalar property. */
    public static final class Creation {
        private final String id;
        private final String type;
        private final Map<String, List<Value>> properties;
        private final Map<String, Map<String, Value>> mapProperties;

        public Creation(String id, String type, Map<String, List<Value>> properties) {
            this(id, type, properties, Map.of());
        }

        /** Captures initial scalar/list properties and the complete contents of each managed map. */
        public Creation(String id, String type, Map<String, List<Value>> properties,
                        Map<String, Map<String, Value>> mapProperties) {
            this.id = JournalSupport.requireName(id, "creation ID");
            this.type = JournalSupport.requireName(type, "creation type");
            Map<String, List<Value>> copy = new LinkedHashMap<>();
            properties.forEach((key, values) -> copy.put(JournalSupport.requireName(key, "property key"), List.copyOf(values)));
            this.properties = Collections.unmodifiableMap(copy);
            Map<String, Map<String, Value>> maps = new LinkedHashMap<>();
            mapProperties.forEach((key, entries) -> {
                if (copy.containsKey(key)) throw new IllegalArgumentException("Duplicate creation property " + key);
                maps.put(JournalSupport.requireName(key, "map property key"), copyEntries(entries));
            });
            this.mapProperties = Collections.unmodifiableMap(maps);
        }

        public String id() { return id; }
        public String type() { return type; }
        public Map<String, List<Value>> properties() { return properties; }
        /** Initial map contents keyed by portable property name. */
        public Map<String, Map<String, Value>> mapProperties() { return mapProperties; }
    }

    /** LIST_ADD indices refer to the resulting list. Other list indices refer to the preceding list. */
    public static final class Operation {
        private final Kind kind;
        private final String target;
        private final String property;
        private final List<Integer> indices;
        private final List<Value> before;
        private final List<Value> after;
        private final List<Creation> created;
        private final Map<String, Value> beforeEntries;
        private final Map<String, Value> afterEntries;

        public Operation(Kind kind, String target, String property, List<Integer> indices,
                         List<Value> before, List<Value> after, List<Creation> created) {
            this(kind, target, property, indices, before, after, created, Map.of(), Map.of());
        }

        /**
         * Captures a keyed edit. A key only in {@code after} is inserted, a key only in
         * {@code before} is removed, and a key in both is replaced. A present null is
         * represented by a {@link ValueKind#NULL} value.
         */
        public static Operation mapEdit(String target, String property,
                                        Map<String, Value> before, Map<String, Value> after) {
            return new Operation(Kind.MAP_EDIT, target, property, List.of(), List.of(), List.of(), List.of(),
                    before, after);
        }

        private Operation(Kind kind, String target, String property, List<Integer> indices,
                          List<Value> before, List<Value> after, List<Creation> created,
                          Map<String, Value> beforeEntries, Map<String, Value> afterEntries) {
            this.kind = Objects.requireNonNull(kind, "operation kind");
            this.target = JournalSupport.requireName(target, "target ID");
            this.property = JournalSupport.requireName(property, "property key");
            this.indices = List.copyOf(indices);
            this.before = List.copyOf(before);
            this.after = List.copyOf(after);
            this.created = List.copyOf(created);
            this.beforeEntries = copyEntries(beforeEntries);
            this.afterEntries = copyEntries(afterEntries);
            validateShape();
        }

        private void validateShape() {
            if (kind == Kind.MAP_EDIT) {
                if (beforeEntries.isEmpty() && afterEntries.isEmpty()) {
                    throw new IllegalArgumentException("A map edit needs at least one affected key");
                }
                return;
            }
            int previous = -1;
            for (int index : indices) {
                if (index <= previous) throw new IllegalArgumentException("List indices must be nonnegative and strictly increasing");
                previous = index;
            }
            int size = indices.size();
            int expectedBefore = kind == Kind.SET ? 1 : kind == Kind.LIST_ADD ? 0 : size;
            int expectedAfter = kind == Kind.SET ? 1 : kind == Kind.LIST_REMOVE ? 0 : size;
            if ((kind == Kind.SET) != indices.isEmpty() || before.size() != expectedBefore || after.size() != expectedAfter) {
                throw new IllegalArgumentException("Invalid value/position count for " + kind);
            }
        }

        public Kind kind() { return kind; }
        public String target() { return target; }
        public String property() { return property; }
        public List<Integer> indices() { return indices; }
        public List<Value> before() { return before; }
        public List<Value> after() { return after; }
        public List<Creation> created() { return created; }
        /** Present entries before a map edit. Empty for scalar and list operations. */
        public Map<String, Value> beforeEntries() { return beforeEntries; }
        /** Present entries after a map edit. Empty for scalar and list operations. */
        public Map<String, Value> afterEntries() { return afterEntries; }
    }

    /** One recorded VMF transaction, or one ungrouped change. */
    public static final class Frame {
        private final long sequence;
        private final List<Operation> operations;

        public Frame(long sequence, List<Operation> operations) {
            this.sequence = sequence;
            this.operations = List.copyOf(operations);
            if (sequence < 1 || this.operations.isEmpty()) {
                throw new IllegalArgumentException("A transaction needs a positive sequence and at least one operation");
            }
        }

        public long sequence() { return sequence; }
        public List<Operation> operations() { return operations; }
    }
}
