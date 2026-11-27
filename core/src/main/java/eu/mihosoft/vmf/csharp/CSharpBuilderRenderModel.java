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
 * Computing and Visualization in Science, 2013, 16(4), 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.EntryKey;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Prepares typed builder input and stages collections without modifying the model graph. */
final class CSharpBuilderRenderModel {
    private static final String RUNTIME = "global::Vmf.Runtime.";
    private static final String GENERIC = "global::System.Collections.Generic.";
    private final CSharpRenderModel source;

    CSharpBuilderRenderModel(CSharpRenderModel source) {
        this.source = source;
    }

    boolean canBuild(Type type) {
        return !type.isImmutable() && !type.isInterfaceOnly();
    }

    Map<String, Object> prepare(Type type, String declarationName) {
        List<Map<String, Object>> properties = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        List<String> containments = new ArrayList<>();
        List<String> containers = new ArrayList<>();
        List<String> validations = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            if (property.getKind() == Property.Kind.CONTAINER && !copiesContainer(property)) continue;

            String name = CSharpRenderModel.propertyName(type, property);
            String field = "__vmfBuilder" + name;
            Map<String, Object> member = property.isCollection()
                    ? collectionMember(property, name, field) : singleMember(property, name, field);
            properties.add(member);
            property.getEntryKey().ifPresent(key -> validations.add(
                    "if (" + field + " is not null) " + validateKey(property, key, requireNonNullEntries(field))));
            List<String> phase;
            switch (property.getKind()) {
                case CONTAINMENT: phase = containments; break;
                case CONTAINER: phase = containers; break;
                default: phase = assignments;
            }
            phase.add((String) member.get("assignment"));
        }
        assignments.addAll(containments);
        assignments.addAll(containers);
        return Map.of("type", declarationName, "modifier", hasInheritedBuilder(type) ? "new " : "",
                "properties", List.copyOf(properties), "assignments", List.copyOf(assignments),
                "validations", List.copyOf(validations), "copy", copyMethods(type, declarationName, properties));
    }

    private Map<String, Object> copyMethods(Type type, String declarationName, List<Map<String, Object>> builderMembers) {
        var properties = type.getEffectiveProperties();
        // A shared inverse observes ownership and has no staged builder value.
        if (!properties.stream().allMatch(property -> property.isSharedContainer() || copiesMember(type, property))) {
            return Map.of();
        }
        var byName = properties.stream().collect(Collectors.toMap(Property::getName, Function.identity()));
        Map<String, Type> candidates = new LinkedHashMap<>();
        candidates.put(declarationName, type);
        candidates.putAll(source.builderSourceApplications(type));
        var imports = new ArrayList<Map<String, Object>>();
        for (var candidate : candidates.entrySet()) {
            Type sourceType = candidate.getValue();
            List<Property> members = sourceType.getEffectiveProperties();
            boolean sharesSlots = members.stream().allMatch(property -> {
                var target = byName.get(property.getName());
                return target != null && property.isCollection() == target.isCollection()
                        && property.isSharedContainer() == target.isSharedContainer()
                        && (property.getDeclaringType().equals(target.getDeclaringType())
                            || importsGetterSlot(target, property)
                            || target.isCollection() && source.hasInheritedManagedCollectionDefault(type, target));
            });
            if (!sharesSlots) continue;
            var statements = members.stream().filter(property -> !property.isSharedContainer()).map(property -> {
                Property target = byName.get(property.getName());
                String name = CSharpRenderModel.propertyName(type, target);
                String value = "source." + CSharpRenderModel.propertyName(sourceType, property);
                if (importsGetterSlot(target, property) && CSharpHierarchyRenderModel.narrowsGetter(target, property)) {
                    value = "(" + source.typeName(target.getValueType()) + "?)" + value;
                }
                String method = property.isCollection() ? "__vmfImport" + name : "With" + name;
                return method + "(" + value + ");";
            }).collect(Collectors.toUnmodifiableList());
            imports.add(Map.of("type", candidate.getKey(), "statements", statements));
        }
        var inputs = builderMembers.stream().collect(Collectors.toMap(
                member -> (String) member.get("name"), member -> (String) member.get("inputType")));
        var preflight = new ArrayList<String>();
        var apply = new ArrayList<String>();
        for (Property property : properties) {
            // Transfer parent links and singular owning slots only when building a new owner.
            if (property.getKind() == Property.Kind.CONTAINER
                    || property.getKind() == Property.Kind.CONTAINMENT && !property.isCollection()) continue;
            String name = CSharpRenderModel.propertyName(type, property);
            String field = "__vmfBuilder" + name;
            String target = "target." + name;
            if (!property.isCollection()) {
                String value = property.getScalarDefault().map(defaultValue -> field + ".Specified ? " + field
                        + ".Value : " + CSharpDefaultRenderModel.literal(defaultValue)).orElse(field + ".Value");
                apply.add(target + " = " + value + ";");
            } else if (property.getKind() != Property.Kind.CONTAINMENT && (isLegacyList(property)
                    || property.getKind() == Property.Kind.REFERENCE && isNullableOrderedList(property))) {
                apply.add("if (!__vmfAppendCollections) " + target + ".Clear();");
                apply.add("if (" + field + " is not null) " + target + ".AddRange(" + field + ");");
            } else {
                String missing = CSharpCapabilities.hasReferenceSetDefault(property)
                        ? CSharpReferenceDefaultRenderModel.cache(name) : "[]";
                String values = "((" + inputs.get(name) + "?)" + field + " ?? " + missing + ")";
                if (copiesOrdinaryContainment(property)) {
                    preflight.add(target + ".ValidateOrdinaryBuilderContents(" + values + ");");
                    apply.add(target + ".ApplyOrdinaryBuilderContents(" + values + ", __vmfAppendCollections);");
                } else if (copiesKeyedContainment(property)) {
                    apply.add(target + ".ApplyKeyedBuilderContents(" + requireNonNullEntries(field + " ?? []")
                            + ", __vmfAppendCollections);");
                } else if (copiesOrderedContainment(property)) {
                    String entries = property.getEntryKey().isPresent() ? requireNonNullEntries(field + " ?? []") : values;
                    preflight.add(target + ".ValidateOrderedBuilderContents(" + entries + ", __vmfAppendCollections);");
                    apply.add(target + ".ApplyOrderedBuilderContents(" + entries + ", __vmfAppendCollections);");
                } else if (copiesUnorderedContainment(property)) {
                    String entries = property.getEntryKey().isPresent() ? requireNonNullEntries(field + " ?? []") : values;
                    preflight.add(target + ".ValidateBuilderContents(" + entries + ", __vmfAppendCollections);");
                    apply.add(target + ".ApplyBuilderContents(" + entries + ", __vmfAppendCollections);");
                } else if (property.getKind() == Property.Kind.MAP) {
                    apply.add(RUNTIME + "ManagedCollectionDefaults.ApplyBuilderMap("
                            + target + ", " + values + ", __vmfAppendCollections);");
                } else {
                    apply.add("if (__vmfAppendCollections) " + target + ".AddRange(" + values + ");");
                    String replace = property.getKind() == Property.Kind.REFERENCE
                            ? target + ".ReplaceBuilderContents(" + values + ")"
                            : CSharpCollectionDefaultRenderModel.copyInto(target, values);
                    apply.add("else " + replace + ";");
                }
            }
        }
        return Map.of("hasCollections", properties.stream().anyMatch(Property::isCollection),
                "imports", List.copyOf(imports), "preflight", List.copyOf(preflight), "apply", List.copyOf(apply));
    }

    private boolean copiesMember(Type owner, Property property) {
        if (property.isGetterOnly() && !source.hasGetterTraitBuilderStorage(owner, property)
                && (!isSingularModelReference(property) || !source.promotesBuilderSetter(owner, property))) return false;
        if (property.getKind() == Property.Kind.CONTAINER) return copiesContainer(property);
        if (property.getKind() == Property.Kind.CONTAINMENT) {
            if (!property.isCollection()) {
                return !property.hasDeclaredDefault() && supportsSharedOrConcreteOwningEnd(property);
            }
            return copiesOrdinaryContainment(property) || copiesKeyedContainment(property)
                    || copiesOrderedContainment(property) || copiesUnorderedContainment(property);
        }
        if (property.getKind() == Property.Kind.REFERENCE) {
            if (property.isCollection() && property.isOrdered() && property.isUnique()) {
                return copiesOrderedReferenceSet(property);
            }
            boolean unopposed = property.getValueType().getKind() == ValueType.Kind.MODEL
                    && property.getOppositeName().isEmpty();
            if (!property.isCollection()) {
                return !property.hasDeclaredDefault() && (unopposed || copiesReciprocalReference(property));
            }
            if (!unopposed && !copiesReciprocalReference(property)) return false;
            return (!property.isOrdered() || isNullableOrderedList(property))
                    && (!property.hasDeclaredDefault() || CSharpCapabilities.hasReferenceSetDefault(property));
        }
        var valueType = property.getValueType();
        boolean externalScalar = property.getKind() == Property.Kind.SCALAR && valueType.getKind() == ValueType.Kind.EXTERNAL;
        if (!valueType.isPortableScalar() && !externalScalar) return false;
        if (!property.isCollection()) {
            return property.getKind() == Property.Kind.SCALAR
                    && (!property.hasDeclaredDefault() || property.getScalarDefault().isPresent()
                        || CSharpCapabilities.bindsExecutableDefault(property));
        }
        return CSharpScalarCollections.isScalarCollection(property)
                && (property.getKind() != Property.Kind.MAP || property.getKeyType().isPortableScalar())
                && (!property.hasDeclaredDefault() || property.getCollectionDefault().isPresent()
                    || CSharpCapabilities.bindsExecutableCollectionDefault(property));
    }

    private static boolean importsGetterSlot(Property target, Property inherited) {
        return inherited.isGetterOnly() && isSingularModelReference(target) && isSingularModelReference(inherited)
                && (target.getValueType().getModelType().equals(inherited.getValueType().getModelType())
                    || CSharpHierarchyRenderModel.narrowsGetter(target, inherited));
    }

    private static boolean isSingularModelReference(Property property) {
        var value = property.getValueType();
        return property.getKind() == Property.Kind.REFERENCE && !property.isCollection()
                && property.getOppositeName().isEmpty() && value.getKind() == ValueType.Kind.MODEL
                && value.getArguments().isEmpty();
    }

    private boolean copiesReciprocalReference(Property property) {
        var value = property.getValueType();
        if (property.getOppositeName().isEmpty() || value.getKind() != ValueType.Kind.MODEL
                || !value.getArguments().isEmpty()) return false;
        var target = source.findType(value.getModelType());
        if (source.isCompanionType(target)) return false;
        return target.getEffectiveProperties().stream().anyMatch(opposite -> {
            if (!opposite.getName().equals(property.getOppositeName()) || opposite.getKind() != Property.Kind.REFERENCE
                    || !opposite.getOppositeName().equals(property.getName())) return false;
            if (!property.isCollection() && !opposite.isCollection()) return true;
            if (target.isInterfaceOnly() || !isConcreteModelEnd(opposite)) return false;
            if (property.isCollection() && opposite.isCollection()) {
                return !property.getDeclaringType().equals(opposite.getDeclaringType())
                        && isOrdinaryReferenceBuilderList(property) && isOrdinaryReferenceBuilderList(opposite);
            }
            return isOrdinaryReferenceBuilderList(property.isCollection() ? property : opposite);
        });
    }

    private static boolean isOrdinaryReferenceBuilderList(Property property) {
        return isLegacyList(property) && !property.hasDeclaredDefault();
    }

    private boolean copiesOrderedReferenceSet(Property property) {
        if (!isConcreteModelEnd(property)
                || property.hasDeclaredDefault() && !CSharpCapabilities.hasReferenceSetDefault(property)) return false;
        if (property.getOppositeName().isEmpty()) return true;
        if (property.getUpperBound() >= 0) return false;
        return source.findType(property.getValueType().getModelType()).getEffectiveProperties().stream()
                .anyMatch(opposite -> opposite.getName().equals(property.getOppositeName())
                        && opposite.getKind() == Property.Kind.REFERENCE && opposite.isCollection()
                        && opposite.isUnique() && !opposite.isOrdered()
                        && opposite.getOppositeName().equals(property.getName()));
    }

    private boolean copiesKeyedContainment(Property property) {
        return property.getKind() == Property.Kind.CONTAINMENT && property.isCollection()
                && property.isOrdered() && !property.isUnique() && property.getEntryKey().isPresent()
                && property.getUpperBound() < 0 && !property.hasDeclaredDefault()
                && isUnopposedConcreteModelEnd(property);
    }

    private boolean copiesOrdinaryContainment(Property property) {
        return CSharpRenderModel.isOrdinaryContainment(property) && property.isOrdered()
                && !property.hasDeclaredDefault() && (supportsSharedOrConcreteOwningEnd(property)
                    || hasExactWritableAbstractInverse(property));
    }

    private boolean copiesOrderedContainment(Property property) {
        return property.getKind() == Property.Kind.CONTAINMENT && property.isCollection()
                && property.isOrdered() && property.isUnique()
                && !property.hasDeclaredDefault() && isConcreteModelEnd(property);
    }

    private boolean copiesUnorderedContainment(Property property) {
        return CSharpRenderModel.isUnorderedContainment(property) && !property.hasDeclaredDefault()
                && (isConcreteModelEnd(property) || property.getEntryKey().isPresent()
                    && property.getOppositeName().isEmpty() && source.usesGetterContract(property.getValueType()));
    }

    private boolean copiesContainer(Property property) {
        return !property.isGetterOnly() && !property.isSharedContainer() && !property.hasDeclaredDefault()
                && isConcreteModelEnd(property);
    }

    private boolean supportsSharedOrConcreteOwningEnd(Property property) {
        if (isConcreteModelEnd(property)) return true;
        var value = property.getValueType();
        if (value.getKind() != ValueType.Kind.MODEL || !value.getArguments().isEmpty()) return false;
        var target = source.findType(value.getModelType());
        return target.isInterfaceOnly() && !source.isCompanionType(target)
                && target.getEffectiveProperties().stream().anyMatch(inverse -> inverse.isSharedContainer()
                    && inverse.getName().equals(property.getOppositeName()));
    }

    private boolean hasExactWritableAbstractInverse(Property property) {
        var value = property.getValueType();
        if (property.getOppositeName().isEmpty() || value.getKind() != ValueType.Kind.MODEL
                || !value.getArguments().isEmpty()) return false;
        var target = source.findType(value.getModelType());
        return target.isInterfaceOnly() && !source.isCompanionType(target)
                && target.getEffectiveProperties().stream().anyMatch(inverse -> inverse.getKind() == Property.Kind.CONTAINER
                    && !inverse.isGetterOnly() && !inverse.hasDeclaredDefault() && isConcreteModelEnd(inverse)
                    && inverse.getName().equals(property.getOppositeName())
                    && inverse.getOppositeName().equals(property.getName())
                    && inverse.getValueType().getModelType().equals(property.getDeclaringType()));
    }

    private boolean isUnopposedConcreteModelEnd(Property property) {
        return property.getOppositeName().isEmpty() && isConcreteModelEnd(property);
    }

    private boolean isConcreteModelEnd(Property property) {
        var value = property.getValueType();
        if (value.getKind() != ValueType.Kind.MODEL || !value.getArguments().isEmpty()) return false;
        var target = source.findType(value.getModelType());
        return !target.isInterfaceOnly() && !source.isCompanionType(target);
    }

    private static boolean isLegacyList(Property property) {
        return property.getUpperBound() < 0 && isNullableOrderedList(property);
    }

    private static boolean isNullableOrderedList(Property property) {
        return property.isOrdered() && !property.isUnique() && property.allowsNullElements();
    }

    private boolean hasInheritedBuilder(Type type) {
        if (type.getSuperTypes().isEmpty()) return false;
        Type primary = source.findType(type.getSuperTypes().get(0));
        return canBuild(primary) || hasInheritedBuilder(primary);
    }

    private Map<String, Object> singleMember(Property property, String name, String field) {
        String type = source.propertyValueTypeName(property);
        if (!property.getValueType().isPrimitive()) type += "?";

        var nested = CSharpNestedBuilderRenderModel.render(source, property, name);
        String value = nested.isEmpty() ? field + ".Value" : nested.get("field") + " is null ? "
                + field + ".Value : " + nested.get("build");
        return Map.of("name", name, "field", field, "collection", false, "inputType", type,
                "fieldType", "(bool Specified, " + type + " Value)",
                "fieldInitializer", CSharpCapabilities.bindsExecutableDefault(property) ? " = (true, "
                        + CSharpExecutableDefaultRenderModel.accessor(name) + "())" : "",
                "nested", nested, "assignment", "if (" + field + ".Specified) result." + name + " = " + value + ";");
    }

    private Map<String, Object> collectionMember(Property property, String name, String field) {
        boolean scalar = CSharpScalarCollections.isScalarCollection(property);
        boolean map = property.getKind() == Property.Kind.MAP;
        boolean keyed = property.getEntryKey().isPresent();
        boolean distinct = keyed || property.isUnique() && (!scalar || property.isOrdered());
        String value = source.propertyValueTypeName(property);
        String entry = scalar ? CSharpScalarCollections.valueType(source, property)
                : property.allowsNullElements() ? value + "?" : value;
        if (map) {
            entry = GENERIC + "KeyValuePair<" + CSharpScalarCollections.keyType(source, property) + ", " + entry + ">";
        }
        String type = scalar ? CSharpScalarCollections.mutableType(source, property) : referenceBufferType(property, value);
        String fieldType = keyed ? GENERIC + "List<" + value + "?>" : type;
        String inputType = GENERIC + "IEnumerable<" + entry + ">";
        String importType = keyed ? GENERIC + "IEnumerable<" + value + "?>" : inputType;
        String options = scalar ? CSharpScalarCollections.constructorArguments(property) : "";
        String receiver = "result." + name;
        boolean hasDefault = CSharpCapabilities.hasManagedCollectionDefault(property);
        var nested = CSharpNestedBuilderRenderModel.render(source, property, name);
        String entries = keyed ? requireNonNullEntries(field) : field;
        String assignment;
        if (scalar && (map || !property.allowsNullElements())) {
            String values = "((" + inputType + "?)" + field + " ?? [])";
            String replace = CSharpCollectionDefaultRenderModel.initialize(property, receiver, values);
            assignment = "if (" + field + " is not null || " + receiver + ".Count != 0) " + replace + ";";
        } else {
            assignment = "if (" + field + " is not null) " + receiver + ".AddRange(" + entries + ");";
        }
        if (CSharpCapabilities.hasReferenceSetDefault(property)) {
            String cache = CSharpReferenceDefaultRenderModel.cache(name);
            String values = "(" + GENERIC + "IEnumerable<" + entry + ">?)" + field + " ?? " + cache;
            if (!nested.isEmpty()) {
                values = nested.get("field") + " is null ? " + values + " : " + nested.get("build");
            }
            assignment = RUNTIME + "ReferenceCollectionDefaults.Store(result, "
                    + CSharpSnapshotRenderModel.literal(property.getName()) + ", " + cache + "); "
                    + receiver + ".ReplaceBuilderContents(" + values + ");";
        } else if (!nested.isEmpty()) {
            assignment = "if (" + nested.get("field") + " is not null) " + receiver + ".AddRange("
                    + nested.get("build") + "); else " + assignment;
        }
        return Map.ofEntries(Map.entry("name", name), Map.entry("field", field), Map.entry("collection", true),
                Map.entry("inputType", inputType), Map.entry("importType", importType),
                Map.entry("fieldType", fieldType + "?"), Map.entry("keyed", keyed),
                Map.entry("orderedKeyed", keyed && property.isUnique()),
                Map.entry("stagedValues", keyed ? "new " + fieldType + "(prepared)" : "prepared"),
                Map.entry("create", "new " + type + "(" + options + ")"), Map.entry("map", map),
                Map.entry("distinct", distinct), Map.entry("keyCheck", property.getEntryKey().map(key ->
                        validateKey(property, key, "prepared")).orElse("")),
                Map.entry("fieldInitializer", hasDefault ? " = " + CSharpCollectionDefaultRenderModel.evaluate(name) : ""),
                Map.entry("referenceDefault", CSharpCapabilities.hasReferenceSetDefault(property)
                        ? CSharpReferenceDefaultRenderModel.cache(name) : ""),
                Map.entry("nested", nested), Map.entry("assignment", assignment));
    }

    private String validateKey(Property property, EntryKey key, String collection) {
        return RUNTIME + "ContainmentKeys.Validate(" + collection + ", "
                + CSharpKeyedRenderModel.selector(source, property, key) + ", keyUsesIdentity: " + key.usesIdentity() + ");";
    }

    private static String requireNonNullEntries(String collection) {
        return RUNTIME + "ContainmentKeys.RequireNonNullEntries(" + collection + ")";
    }

    private static String referenceBufferType(Property property, String value) {
        String implementation = CSharpRenderModel.isOrdinaryContainment(property) ? "ReferenceList"
                : CSharpRenderModel.isUnorderedContainment(property) ? "ReferenceSet"
                : property.getKind() == Property.Kind.CONTAINMENT
                ? "OrderedReferenceSet" : CSharpRenderModel.referenceCollectionName(property);
        return RUNTIME + implementation + "<" + value + ">";
    }
}
