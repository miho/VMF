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

import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Validates a finished VMF model without changing it. */
public final class ModelValidator {
    static final ModelValidator DEFAULT = builder().build();

    private final List<RegisteredRule<?>> objectRules;
    private final List<ValidationRule<VObject>> graphRules;
    private final boolean followReferences;

    private ModelValidator(Builder builder) {
        objectRules = List.copyOf(builder.objectRules);
        graphRules = List.copyOf(builder.graphRules);
        followReferences = builder.followReferences;
    }

    /** Builds a validator with multiplicity and declared value constraints enabled. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Checks the root and its descendants in depth-first containment order.
     * If reference following is enabled, targets outside that tree are checked
     * afterwards, once per object identity. Paths identify the first visit.
     * Call while the model is stable and keep registered callbacks free of model edits.
     */
    public ValidationReport validate(VObject root) {
        Objects.requireNonNull(root, "root");
        List<ValidationIssue> issues = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Location> contained = new ArrayDeque<>();
        Deque<Location> referenced = new ArrayDeque<>();
        contained.push(new Location(root, ""));

        while (!contained.isEmpty() || !referenced.isEmpty()) {
            Location location = contained.isEmpty() ? referenced.removeFirst() : contained.pop();
            if (!visited.add(VMFIterator.unwrapIfReadOnlyInstanceForIdentityCheck(location.object))) continue;

            checkProperties(location, issues);
            if (!objectRules.isEmpty()) {
                var context = new ValidationContext(root, location.object, location.path, issues);
                for (RegisteredRule<?> rule : objectRules) rule.validate(location.object, context);
            }

            var internal = (VObjectInternal) location.object;
            int[] childProperties = internal._vmf_getChildrenIndices();
            var children = new ArrayList<Location>();
            for (int id : childProperties) addTargets(children, location, internal, id);
            for (int i = children.size() - 1; i >= 0; i--) contained.push(children.get(i));
            if (followReferences) {
                int[] parents = internal._vmf_getParentIndices();
                var targets = new ArrayList<Location>();
                for (int id : internal._vmf_getIndicesOfPropertiesWithModelTypeOrElementTypes()) {
                    if (!contains(childProperties, id) && !contains(parents, id)) {
                        addTargets(targets, location, internal, id);
                    }
                }
                referenced.addAll(targets);
            }
        }

        if (!graphRules.isEmpty()) {
            var context = new ValidationContext(root, root, "", issues);
            for (ValidationRule<VObject> rule : graphRules) rule.validate(root, context);
        }
        return new ValidationReport(issues);
    }

    private static void checkProperties(Location location, List<ValidationIssue> issues) {
        VObject object = location.object;
        for (Property property : ReflectImpl.readProperties(object.vmf().reflect())) {
            var multiplicity = MultiplicityViolation.check(object, property);
            if (multiplicity != null) {
                issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, "vmf.multiplicity",
                        ValidationContext.childPath(location.path, property.getName()),
                        multiplicity.toString(), object, property, multiplicity));
            }

            var constraints = property.getConstraints();
            if (constraints.isEmpty()) continue;
            Object value = property.get();
            for (ValueConstraint constraint : constraints) {
                if (!constraint.isSatisfiedBy(value)) {
                    issues.add(new ValidationIssue(ValidationIssue.Severity.ERROR, constraint.getCode(),
                            ValidationContext.childPath(location.path, property.getName()),
                            constraint.toString(), object, property, null));
                }
            }
        }
    }

    private static void addTargets(List<Location> targets, Location parent, VObjectInternal owner, int id) {
        Object value = owner._vmf_getPublicPropertyValueById(id);
        String propertyName = owner._vmf_getPropertyNames()[id];
        if (value instanceof VObject) {
            targets.add(new Location((VObject) value, ValidationContext.childPath(parent.path, propertyName)));
        } else if (value instanceof Iterable) {
            String path = ValidationContext.childPath(parent.path, propertyName);
            int index = 0;
            for (Object member : (Iterable<?>) value) {
                if (member instanceof VObject) targets.add(new Location((VObject) member, path + "/" + index));
                index++;
            }
        }
    }

    private static boolean contains(int[] values, int expected) {
        for (int value : values) {
            if (value == expected) return true;
        }
        return false;
    }

    private static final class Location {
        final VObject object;
        final String path;

        Location(VObject object, String path) {
            this.object = object;
            this.path = path;
        }
    }

    private static final class RegisteredRule<T extends VObject> {
        final Class<T> type;
        final ValidationRule<? super T> rule;

        RegisteredRule(Class<T> type, ValidationRule<? super T> rule) {
            this.type = Objects.requireNonNull(type, "type");
            this.rule = Objects.requireNonNull(rule, "rule");
        }

        void validate(VObject object, ValidationContext context) {
            if (type.isInstance(object)) rule.validate(type.cast(object), context);
        }
    }

    /** Configures the rules and traversal of a ModelValidator. */
    public static final class Builder {
        private final List<RegisteredRule<?>> objectRules = new ArrayList<>();
        private final List<ValidationRule<VObject>> graphRules = new ArrayList<>();
        private boolean followReferences;

        private Builder() {}

        /**
         * Adds a rule for objects assignable to the model interface, including subtypes.
         * To match a read-only view, register its read-only interface or a shared supertype.
         */
        public <T extends VObject> Builder withRule(Class<T> type, ValidationRule<? super T> rule) {
            objectRules.add(new RegisteredRule<>(type, rule));
            return this;
        }

        /** Adds a rule run once on the root after all object rules. */
        public Builder withGraphRule(ValidationRule<VObject> rule) {
            graphRules.add(Objects.requireNonNull(rule, "rule"));
            return this;
        }

        /** Includes non-containment references, excluding the parent navigation properties. */
        public Builder withFollowReferences(boolean enabled) {
            followReferences = enabled;
            return this;
        }

        /** Returns a validator with the current configuration. */
        public ModelValidator build() {
            return new ModelValidator(this);
        }
    }
}
