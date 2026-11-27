/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 */
package eu.mihosoft.vmf.core.codegen;

import eu.mihosoft.vmf.core.PropertyConstraint;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.TypeParameter;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Validates whether inherited model properties can share a single target slot. */
final class PortableInheritance {
    private PortableInheritance() {
    }

    static void checkCSharpComposition(ResolvedModel model, Type owner, List<String> diagnostics) {
        String name = owner.getQualifiedName();
        if (!owner.getTypeParameters().isEmpty() && !composesOpenRoles(model, owner)) {
            diagnostics.add(name + " needs closed model type arguments to compose multiple bases");
        }
        if (!owner.isImmutable()) {
            var seen = new HashSet<String>();
            for (var application : owner.getSuperTypeApplications().subList(1, owner.getSuperTypeApplications().size())) {
                checkSecondaryClosure(model, application.getModelType(), seen, name, diagnostics);
            }
        }
        checkMergedProperties(model, owner, diagnostics);
    }

    private static boolean isScalarDataProperty(Property property) {
        if (property.getKind() != Property.Kind.SCALAR
                && (property.getKind() != Property.Kind.MAP || !isScalarValue(property.getKeyType()))) return false;
        return isScalarValue(property.getValueType());
    }

    private static boolean isScalarValue(ValueType type) {
        return type.isPortableScalar() || type.getKind() == ValueType.Kind.PARAMETER;
    }

    private static boolean composesOpenRoles(ResolvedModel model, Type owner) {
        if (!owner.isInterfaceOnly() || !isDataRole(owner) || !hasSupportedOpenParameters(owner)) return false;

        var applications = owner.getSuperTypeApplications();
        boolean hasModelParameters = owner.getTypeParameters().stream().anyMatch(PortableInheritance::isNamedModelParameter);
        if (!hasModelParameters && !owner.getEffectiveProperties().stream()
                .allMatch(PortableInheritance::isScalarDataProperty)) return false;
        if (hasIndependentOpenRoles(model, applications, hasModelParameters)) return true;
        if (!hasNamedModelParameters(owner) || applications.size() != 2 || !owner.getProperties().isEmpty()) return false;

        var primaryApplication = applications.get(0);
        var secondaryApplication = applications.get(1);
        var primary = model.findType(primaryApplication.getModelType()).orElse(null);
        var secondary = model.findType(secondaryApplication.getModelType()).orElse(null);
        if (!forwardsNamedModelRole(primary, primaryApplication)
                || !forwardsNamedModelRole(secondary, secondaryApplication)) return false;
        var primaryNames = primary.getEffectiveProperties().stream().map(Property::getName).collect(Collectors.toSet());
        return sharesOpenOwningBase(model, primary, primaryApplication, secondary, secondaryApplication, primaryNames);
    }

    private static boolean hasIndependentOpenRoles(ResolvedModel model, List<ValueType> applications,
                                                   boolean hasModelParameters) {
        var names = new HashSet<String>();
        for (int index = 0; index < applications.size(); index++) {
            var application = applications.get(index);
            var role = model.findType(application.getModelType()).orElse(null);
            if (role == null || !isDataRole(role) || !hasSupportedOpenParameters(role)) return false;
            boolean nongenericPrimary = hasModelParameters && index == 0 && role.getTypeParameters().isEmpty();
            boolean modelRole = role.getTypeParameters().stream().anyMatch(PortableInheritance::isNamedModelParameter);
            if (modelRole && (!hasModelParameters || !role.isInterfaceOnly()
                    || application.getArguments().stream().anyMatch(argument -> argument.getKind() != ValueType.Kind.PARAMETER))) {
                return false;
            }
            for (Property property : role.getEffectiveProperties()) {
                boolean supported = nongenericPrimary || isScalarDataProperty(property)
                        || modelRole && (supportsOpenDataProperty(property) || isOpenOwningSet(property));
                if (!supported || !names.add(property.getName())) return false;
            }
        }
        return true;
    }

    private static boolean forwardsNamedModelRole(Type role, ValueType application) {
        return role != null && role.isInterfaceOnly() && !role.isImmutable() && hasNamedModelParameters(role)
                && application.getArguments().size() == role.getTypeParameters().size()
                && application.getArguments().stream().allMatch(argument -> argument.getKind() == ValueType.Kind.PARAMETER);
    }

    private static boolean sharesOpenOwningBase(ResolvedModel model, Type primary, ValueType primaryApplication,
                                                Type secondary, ValueType secondaryApplication, Set<String> primaryNames) {
        if (primary.getSuperTypeApplications().size() != 1 || secondary.getSuperTypeApplications().size() != 1) return false;

        var primaryBase = primary.getSuperTypeApplications().get(0);
        var secondaryBase = secondary.getSuperTypeApplications().get(0);
        var shared = model.findType(primaryBase.getModelType()).orElse(null);
        if (!primaryBase.getModelType().equals(secondaryBase.getModelType())
                || !forwardsNamedModelRole(shared, primaryBase) || !forwardsNamedModelRole(shared, secondaryBase)
                || !shared.getSuperTypes().isEmpty()) return false;

        // Both routes must reach the same constructed role so the owning slot can stay in the primary base.
        var primaryBindings = bindings(primary, primaryApplication);
        var secondaryBindings = bindings(secondary, secondaryApplication);
        var primaryArguments = primaryBase.getArguments().stream().map(value -> typeKey(value, primaryBindings)).collect(Collectors.toList());
        var secondaryArguments = secondaryBase.getArguments().stream().map(value -> typeKey(value, secondaryBindings)).collect(Collectors.toList());
        if (!primaryArguments.equals(secondaryArguments)) return false;

        var sharedProperties = shared.getEffectiveProperties();
        var sharedNames = sharedProperties.stream().map(Property::getName).collect(Collectors.toSet());
        return sharedProperties.stream().filter(PortableInheritance::isOpenOwningSet).count() == 1
                && sharedProperties.stream().allMatch(property -> isOpenOwningSet(property) || supportsOpenDataProperty(property))
                && primary.getProperties().stream().allMatch(property -> supportsOpenDataProperty(property) && !sharedNames.contains(property.getName()))
                && secondary.getProperties().stream().allMatch(property -> supportsOpenDataProperty(property) && !primaryNames.contains(property.getName()));
    }

    private static boolean isOpenOwningSet(Property property) {
        return property.getKind() == Property.Kind.CONTAINMENT && property.isCollection() && property.isOrdered()
                && property.isUnique() && !property.allowsNullElements() && property.getOppositeName().isEmpty()
                && property.getEntryKey().isEmpty() && property.getValueType().getKind() == ValueType.Kind.PARAMETER;
    }

    private static boolean supportsOpenDataProperty(Property property) {
        if (property.getKind() == Property.Kind.SCALAR) return !property.isCollection();
        return property.getKind() == Property.Kind.REFERENCE && property.getOppositeName().isEmpty()
                && (!property.isCollection() || property.isOrdered() && !property.isUnique() && property.allowsNullElements());
    }

    private static boolean hasNamedModelParameters(Type type) {
        return !type.getTypeParameters().isEmpty()
                && type.getTypeParameters().stream().allMatch(PortableInheritance::isNamedModelParameter);
    }

    private static boolean hasSupportedOpenParameters(Type type) {
        return type.getTypeParameters().stream().allMatch(parameter ->
                parameter.getBound() == null || parameter.isNumberBound() || isNamedModelParameter(parameter));
    }

    private static boolean isNamedModelParameter(TypeParameter parameter) {
        return parameter.getBounds().size() == 1 && parameter.getBound().getKind() == ValueType.Kind.MODEL
                && parameter.getBound().getArguments().isEmpty();
    }

    static void checkCSharpImmutableBases(ResolvedModel model, Type owner, List<String> diagnostics) {
        var parents = owner.getSuperTypes();
        boolean composedValues = parents.size() > 1 && isComposableImmutableValue(model, owner)
                && model.findType(parents.get(0)).filter(parent -> parent.isImmutable() && !parent.isInterfaceOnly()).isPresent();
        // Leading getter-only data contracts do not compete with the first concrete binding.
        boolean canSelectPrimary = true;
        for (int i = 0; i < parents.size(); i++) {
            var parent = model.findType(parents.get(i)).orElse(null);
            if (parent == null) continue;
            if (parent.isGetterOnlyTrait()) {
                canSelectPrimary &= isNongenericData(parent);
                continue;
            }
            boolean primary = canSelectPrimary && parent.isImmutable() && !parent.isInterfaceOnly();
            canSelectPrimary = false;
            if (primary) continue;
            if (i > 0 && composedValues && parent.isImmutable()) continue;
            diagnostics.add(owner.getQualifiedName() + " can extend only one primary immutable declaration"
                    + " and nongeneric getter-only traits, found " + parent.getQualifiedName());
        }
    }

    private static boolean isComposableImmutableValue(ResolvedModel model, Type type) {
        // Immutable construction never invokes the class-level hook. Its callable methods
        // still use their declared binding and can be composed through the existing call contracts.
        if (!type.isImmutable() || type.isInterfaceOnly() || !type.getTypeParameters().isEmpty()) return false;
        for (String name : type.getSuperTypes()) {
            var parent = model.findType(name).orElse(null);
            if (parent == null) return false;
            if (parent.isGetterOnlyTrait()) continue;
            if (!isComposableImmutableValue(model, parent)) return false;
        }
        return true;
    }

    private static void checkSecondaryClosure(ResolvedModel model, String parent, Set<String> seen,
                                              String owner, List<String> diagnostics) {
        if (!seen.add(parent)) return;
        var type = model.findType(parent).orElse(null);
        if (type == null || CSharpCapabilities.isBoundBehaviorRole(model, type)
                || CSharpCapabilities.hasBoundBehaviorBranches(model, type)) return;
        if (type.isImmutable() || (!isInheritedBehaviorTrait(model, type)
                && (type.getConstructionHook().isPresent()
                    || (!type.getEffectiveMethods().isEmpty() && !CSharpCapabilities.isUnboundBehaviorTrait(type))))) {
            diagnostics.add(owner + " needs a mutable data-only secondary base instead of " + type.getQualifiedName());
            return;
        }
        for (String inherited : type.getSuperTypes()) {
            checkSecondaryClosure(model, inherited, seen, owner, diagnostics);
        }
    }

    static boolean hasClosedDataOnlySecondaries(ResolvedModel model, Type type) {
        return hasClosedDataOnlyAncestors(model, type.getSuperTypes().stream().skip(1));
    }

    static boolean hasClosedDataOnlyAncestors(ResolvedModel model, Stream<String> parents) {
        var seen = new HashSet<String>();
        return parents.allMatch(parent -> isClosedDataOnly(model, parent, seen));
    }

    private static boolean isClosedDataOnly(ResolvedModel model, String name, Set<String> seen) {
        if (!seen.add(name)) return true;
        return model.findType(name).filter(PortableInheritance::isNongenericData)
                .map(type -> type.getSuperTypes().stream().allMatch(parent -> isClosedDataOnly(model, parent, seen)))
                .orElse(false);
    }

    static boolean isInheritedBehaviorTrait(ResolvedModel model, Type type) {
        return type.isInterfaceOnly() && !type.isImmutable() && type.getTypeParameters().isEmpty()
                && type.getDeclaredMethods().isEmpty() && type.getEffectiveMethods().stream().allMatch(method ->
                        isAbstractBehaviorRole(model, method.getDeclaringType()))
                && type.getConstructionHook().map(hook ->
                        !hook.getDeclaringType().equals(type.getName()) && isAbstractBehaviorRole(model, hook.getDeclaringType()))
                        .orElse(true);
    }

    static boolean isAbstractBehaviorRole(ResolvedModel model, String name) {
        return model.findType(name).filter(type -> type.isInterfaceOnly()
                && CSharpCapabilities.isBoundBehaviorRole(model, type)).isPresent();
    }

    private static void checkMergedProperties(ResolvedModel model, Type owner, List<String> diagnostics) {
        var effective = owner.getEffectiveProperties().stream()
                .collect(Collectors.toMap(Property::getName, Function.identity(), (first, next) -> first));
        var redeclaredKeys = owner.getProperties().stream().filter(property -> property.getEntryKey().isPresent())
                .map(Property::getName).collect(Collectors.toSet());
        var inherited = new HashMap<String, InheritedSlot>();
        var reported = new HashSet<String>();

        for (var application : owner.getSuperTypeApplications()) {
            var parent = model.findType(application.getModelType()).orElse(null);
            if (parent == null || parent.getTypeParameters().size() != application.getArguments().size()) continue;
            var bindings = bindings(parent, application);
            for (var property : parent.getEffectiveProperties()) {
                String name = property.getName();
                var contract = contract(parent, property, bindings, true);
                var prior = inherited.get(name);
                if (prior == null) {
                    inherited.put(name, new InheritedSlot(contract, scalarDefaultMetadata(parent, property, bindings)));
                    continue;
                }
                var slot = effective.get(name);
                // Contributing parents must still agree on their own wire and storage contract.
                boolean compareWireName = !usesLocalScalarWireName(owner, slot)
                        && (!redeclaredKeys.contains(name) || property.getEntryKey().isEmpty());
                var expected = compareWireName ? contract : contract(parent, property, bindings, false);
                if ((!prior.contract.equals(contract) || !expected.equals(contract(owner, slot, Map.of(), compareWireName)))
                        && !selectsCompatibleScalarDefault(owner, slot, prior, parent, property, bindings)
                        && !hasExplicitCovariantGetterMerge(model, owner, name)
                        && !promotesKeyedSequence(model, owner, slot)
                        && reported.add(name)) {
                    diagnostics.add(owner.getQualifiedName() + "." + name + " inherits incompatible property metadata");
                }
            }
        }
    }

    private static boolean selectsCompatibleScalarDefault(Type owner, Property slot, InheritedSlot first,
                                                          Type parent, Property later, Map<String, ValueType> bindings) {
        var metadata = scalarDefaultMetadata(owner, slot, Map.of());
        return metadata != null && metadata.equals(first.scalarMetadata)
                && metadata.equals(scalarDefaultMetadata(parent, later, bindings))
                && (declaresScalarLiteral(owner, slot) || first.contract.equals(contract(owner, slot, Map.of(), true)));
    }

    private static boolean declaresScalarLiteral(Type owner, Property property) {
        return !owner.isInterfaceOnly() && owner.getName().equals(property.getDeclaringType())
                && property.getScalarDefault().isPresent();
    }

    private static final class InheritedSlot {
        private final List<?> contract;
        private final List<?> scalarMetadata;

        private InheritedSlot(List<?> contract, List<?> scalarMetadata) {
            this.contract = contract;
            this.scalarMetadata = scalarMetadata;
        }
    }

    static boolean promotesKeyedSequence(ResolvedModel model, Type owner, Property effective) {
        if (effective == null || !ordinaryModelSequence(effective) || effective.getKind() != Property.Kind.CONTAINMENT
                || effective.getEntryKey().isEmpty() || owner.isInterfaceOnly() || !isNongenericData(owner)
                || owner.getSuperTypes().size() < 2 || owner.getProperties().stream().anyMatch(property ->
                        property.getName().equals(effective.getName()))
                || model.getTypes().stream().anyMatch(type -> type.getSuperTypes().contains(owner.getName()))) {
            return false;
        }
        var base = model.findType(owner.getSuperTypes().get(0)).orElseThrow();
        if (!base.isInterfaceOnly() || !isNongenericData(base) || !base.getSuperTypes().isEmpty()) return false;
        var inherited = base.getEffectiveProperties().stream()
                .filter(property -> property.getName().equals(effective.getName())).findFirst().orElse(null);
        if (inherited == null || !ordinaryModelSequence(inherited) || inherited.getKind() != Property.Kind.REFERENCE
                || !inherited.allowsNullElements() || effective.allowsNullElements()
                || inherited.getEntryKey().isPresent() || inherited.isGetterOnly() != effective.isGetterOnly()
                || !inherited.getWireName().equals(effective.getWireName())
                || !inherited.getValueType().getModelType().equals(effective.getValueType().getModelType())
                || inherited.getLowerBound() != effective.getLowerBound()
                || inherited.getUpperBound() != effective.getUpperBound()
                || !inherited.getConstraints().isEmpty() || !effective.getConstraints().isEmpty()
                || !inherited.getFeatures().isEmpty() || !effective.getFeatures().equals(List.of(ResolvedModel.Feature.KEYED))) {
            return false;
        }
        boolean foundOwning = false;
        for (String parentName : owner.getSuperTypes().subList(1, owner.getSuperTypes().size())) {
            var parent = model.findType(parentName).orElseThrow();
            if (!parent.isInterfaceOnly() || !isNongenericData(parent) || !parent.getSuperTypes().isEmpty()) return false;
            for (Property property : parent.getEffectiveProperties()) {
                if (!property.getName().equals(effective.getName())) continue;
                if (!contract(parent, property, Map.of(), true).equals(contract(owner, effective, Map.of(), true))) return false;
                foundOwning = true;
            }
        }
        return foundOwning;
    }

    private static boolean ordinaryModelSequence(Property property) {
        return property.isCollection() && property.isOrdered() && !property.isUnique()
                && property.getOppositeName().isEmpty() && !property.hasDeclaredDefault()
                && property.getValueType().getKind() == ValueType.Kind.MODEL && property.getValueType().getArguments().isEmpty();
    }

    private static boolean isNongenericData(Type type) {
        return type.getTypeParameters().isEmpty() && isDataRole(type);
    }

    private static boolean isDataRole(Type type) {
        return !type.isImmutable() && type.getEffectiveMethods().isEmpty() && type.getConstructionHook().isEmpty();
    }

    private static boolean hasExplicitCovariantGetterMerge(ResolvedModel model, Type owner, String name) {
        if (!owner.isGetterOnlyTrait()) return false;
        var declared = owner.getProperties().stream().filter(property -> property.getName().equals(name)).findFirst();
        return declared.isPresent() && owner.getSuperTypes().stream().map(parent -> model.findType(parent).orElseThrow())
                .allMatch(parent -> parent.getEffectiveProperties().stream().filter(property -> property.getName().equals(name))
                        .allMatch(property -> PortableCapabilities.supportsCovariantTraitGetter(model, owner, parent,
                                declared.get(), property)));
    }

    static boolean sameCovariantGetterMetadata(Property declared, Property inherited) {
        return declared.getWireName().equals(inherited.getWireName()) && declared.getLowerBound() == inherited.getLowerBound()
                && declared.getUpperBound() == inherited.getUpperBound() && declared.getFeatures().equals(inherited.getFeatures())
                && declared.getConstraints().stream().map(PortableInheritance::constraintKey).collect(Collectors.toList())
                        .equals(inherited.getConstraints().stream().map(PortableInheritance::constraintKey).collect(Collectors.toList()));
    }

    private static Map<String, ValueType> bindings(Type parent, ValueType application) {
        var bindings = new HashMap<String, ValueType>();
        for (int i = 0; i < parent.getTypeParameters().size(); i++) {
            bindings.put(parent.getTypeParameters().get(i).getName(), application.getArguments().get(i));
        }
        return bindings;
    }

    /** The key of a value type as a subtype inherits it through the given application of its supertype. */
    static List<?> boundTypeKey(Type parent, ValueType application, ValueType type) {
        if (parent.getTypeParameters().size() != application.getArguments().size()) return typeKey(type, Map.of());
        return typeKey(type, bindings(parent, application));
    }

    static boolean sameStorageSlot(Property declared, Property inherited, Type parent, ValueType application) {
        if (declared.hasDeclaredDefault() || inherited.hasDeclaredDefault()
                || parent.getTypeParameters().size() != application.getArguments().size()) {
            return false;
        }
        return storageContract(declared, Map.of()).equals(storageContract(inherited, bindings(parent, application)));
    }

    static boolean sameManagedDefaultSlot(Type owner, Property property, Type root, Property origin) {
        if (property.getKind() != Property.Kind.MAP && (property.getKind() != Property.Kind.SCALAR
                || !property.isCollection() || !property.isOrdered() || property.isUnique()
                || property.allowsNullElements())) return false;
        if (property.hasDeclaredDefault() && !CSharpCapabilities.hasManagedCollectionDefault(property)) return false;
        return propertyMetadata(owner, property, Map.of(), true, withoutDefault(property))
                .equals(propertyMetadata(root, origin, Map.of(), true, withoutDefault(origin)));
    }

    private static List<ResolvedModel.Feature> withoutDefault(Property property) {
        return property.getFeatures().stream().filter(feature -> feature != ResolvedModel.Feature.DEFAULT_VALUE)
                .collect(Collectors.toList());
    }

    static boolean sameInheritedContract(Type owner, Property declared, Property inherited,
                                          Type parent, ValueType application) {
        boolean compareWireName = !usesLocalScalarWireName(owner, declared)
                && (declared.getEntryKey().isEmpty() || inherited.getEntryKey().isEmpty());
        if (parent.getTypeParameters().size() != application.getArguments().size()) return false;
        var bindings = bindings(parent, application);
        if (contract(owner, declared, Map.of(), compareWireName)
                .equals(contract(parent, inherited, bindings, compareWireName))) return true;

        var metadata = scalarDefaultMetadata(owner, declared, Map.of());
        return declaresScalarLiteral(owner, declared) && metadata != null
                && metadata.equals(scalarDefaultMetadata(parent, inherited, bindings));
    }

    private static boolean usesLocalScalarWireName(Type owner, Property property) {
        return property != null && !owner.isInterfaceOnly() && !owner.isImmutable()
                && owner.getName().equals(property.getDeclaringType())
                && property.getKind() == Property.Kind.SCALAR && !property.isCollection()
                && property.getValueType().isPortableScalar() && !property.hasDeclaredDefault();
    }

    private static List<?> contract(Type owner, Property property, Map<String, ValueType> bindings,
                                    boolean includeWireName) {
        if (property == null) return List.of();
        var defaultValue = property.getScalarDefault().map(value -> Arrays.asList(value.getKind(), value.getValue()));
        return List.of(propertyMetadata(owner, property, bindings, includeWireName, property.getFeatures()), defaultValue);
    }

    private static List<?> scalarDefaultMetadata(Type owner, Property property, Map<String, ValueType> bindings) {
        if (property == null || property.getKind() != Property.Kind.SCALAR || property.isCollection()
                || !property.getValueType().isPortableScalar()
                || property.hasDeclaredDefault() && property.getScalarDefault().isEmpty()) return null;
        return propertyMetadata(owner, property, bindings, true, withoutDefault(property));
    }

    private static List<?> propertyMetadata(Type owner, Property property, Map<String, ValueType> bindings,
                                            boolean includeWireName, List<ResolvedModel.Feature> features) {
        var constraints = property.getConstraints().stream().map(PortableInheritance::constraintKey).collect(Collectors.toList());
        return List.of(includeWireName ? property.getWireName() : "", storageContract(property, bindings),
                restrictsGetter(owner, property), constraints, features);
    }

    private static boolean restrictsGetter(Type owner, Property property) {
        if (!property.isGetterOnly()) return false;
        // A mixed mutable type exposes the same live scalar sequence with or without @GetterOnly.
        return owner.isImmutable() || owner.isGetterOnlyTrait() || property.getKind() != Property.Kind.SCALAR
                || !property.isCollection() || !property.getValueType().isPortableScalar()
                || !property.isOrdered() || property.isUnique() || !property.allowsNullElements();
    }

    private static List<?> storageContract(Property property, Map<String, ValueType> bindings) {
        var key = property.getEntryKey().map(entry -> List.of(entry.getPropertyName(), typeKey(entry.getValueType(), bindings)));
        // Effective key-use and equality-exclusion flags can vary when a generic reference is closed.
        // These flags do not change a property's backing storage.
        return List.of(property.getKind(), typeKey(property.getValueType(), bindings),
                typeKey(property.getKeyType(), bindings), property.isCollection(), property.allowsNullElements(),
                property.isOrdered(), property.isUnique(), property.getLowerBound(), property.getUpperBound(),
                property.getOppositeName(), key);
    }

    private static List<?> typeKey(ValueType type, Map<String, ValueType> bindings) {
        if (type == null) return List.of();
        if (type.getKind() == ValueType.Kind.PARAMETER && bindings.containsKey(type.getParameterName())) {
            return typeKey(bindings.get(type.getParameterName()), Map.of());
        }
        var arguments = type.getArguments().stream().map(argument -> typeKey(argument, bindings)).collect(Collectors.toList());
        return Arrays.asList(type.getKind(), type.getModelType(), type.getSourceType(), type.isPrimitive(),
                type.getParameterName(), arguments);
    }

    private static List<?> constraintKey(PropertyConstraint constraint) {
        return Arrays.asList(constraint.getKind(), constraint.getMin(), constraint.isMinInclusive(), constraint.getMax(),
                constraint.isMaxInclusive(), constraint.getMinLength(), constraint.getMaxLength(), constraint.getPattern(),
                constraint.getSchemaPattern());
    }
}
