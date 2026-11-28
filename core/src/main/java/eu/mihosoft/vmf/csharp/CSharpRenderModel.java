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
 * 181Ã¢â‚¬â€œ192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.TypeParameter;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Map.entry;

/** Lowers the analyzed model to the declarations and checks rendered by the C# template. */
final class CSharpRenderModel {
    private static final Pattern PORTABLE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern RESERVED_PATH_COMPONENT = Pattern.compile(
            "(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])");
    private static final Set<String> RESTRICTED_IDENTIFIERS = Set.of(
            "__arglist", "__makeref", "__reftype", "__refvalue");
    private static final Set<String> GENERATED_MEMBERS = Set.of(
            "Validate", "ValidateInto", "ValidateLocal", "VmfChanges", "AsReadOnly", "__vmfContainerLink", "__vmfReadOnlyView",
            "__vmfCreateReadOnlyView", "__VmfReadOnly", "ContentEquals", "ContentHashCode", "__vmfContentEquals",
            "Builder", "NewBuilder", "VmfContainmentLink");
    private static final Set<String> INHERITED_MEMBERS = Set.of(
            "Equals", "Finalize", "GetHashCode", "GetType", "MemberwiseClone", "ReferenceEquals", "ToString");

    private final String namespace;
    private final Map<String, String> typeNames = new LinkedHashMap<>();
    private final ResolvedModel model;
    private final CSharpReadOnlyRenderModel readOnly = new CSharpReadOnlyRenderModel(this);
    private final CSharpImmutableRenderModel immutable = new CSharpImmutableRenderModel(this);
    private final CSharpBuilderRenderModel builder = new CSharpBuilderRenderModel(this);
    private final CSharpBehaviorRenderModel behavior = new CSharpBehaviorRenderModel(this);
    private final CSharpImmutableBehaviorRenderModel immutableBehavior = new CSharpImmutableBehaviorRenderModel(this, behavior);
    private final List<Map<String, Object>> types;
    private final CSharpSnapshotRenderModel snapshots;
    private final List<Map<String, Object>> reflection;
    private final CSharpHierarchyRenderModel hierarchy;
    private final Set<Type> ownedRoleRoots;
    private final CSharpBehaviorHierarchyRenderModel behaviorHierarchy;
    private final CSharpSharedBehaviorRenderModel sharedBehavior;
    private final Set<Type> nullableSummaries = new HashSet<>();
    private final CSharpExternalRenderModel externalTypes;
    private final Map<Type, Set<String>> defaultInitializers = new java.util.HashMap<>();
    private final Map<Type, CSharpExecutableDefaultRenderModel.DeferredConstructor> executableDefaultFamilies;
    private final Map<Type, Map<String, String>> behaviorProviderNames = new java.util.HashMap<>();

    CSharpRenderModel(ResolvedModel model) {
        this.model = model;
        externalTypes = new CSharpExternalRenderModel(model);
        namespace = Arrays.stream(model.getNamespace().split("\\.", -1))
                .map(part -> namespacePart(part, model.getNamespace()))
                .collect(Collectors.joining("."));
        Set<String> emittedTypeNames = new HashSet<>();
        emittedTypeNames.add("ModelSnapshot");
        for (Type type : model.getTypes()) {
            String name = emittedModelName(type);
            if (GENERATED_MEMBERS.contains(name) || !emittedTypeNames.add(name)) {
                throw invalidName(type.getQualifiedName(), name, "conflicts with a generated name");
            }
            typeNames.put(type.getName(), name);
        }

        hierarchy = new CSharpHierarchyRenderModel(this, model);
        for (Type type : model.getTypes()) {
            if (CSharpImmutableBehaviorRenderModel.hasCalls(type)) {
                String calls = CSharpImmutableBehaviorRenderModel.callsName(typeName(type));
                if (!emittedTypeNames.add(calls)) {
                    throw invalidName(type.getQualifiedName(), calls, "conflicts with another C# type");
                }
            }
            if (!type.isImmutable() && !emittedTypeNames.add(readOnly.interfaceName(type))) {
                throw invalidName(type.getQualifiedName(), readOnly.interfaceName(type),
                        "conflicts with another C# type");
            }
            if (hierarchy.contains(type.getName()) && !emittedTypeNames.add(hierarchy.companionName(type))) {
                throw invalidName(type.getQualifiedName(), hierarchy.companionName(type),
                        "conflicts with another C# type");
            }
        }

        List<Type> sortedTypes = model.getTypes().stream()
                .sorted(Comparator.comparing(Type::getName)).collect(Collectors.toList());
        sortedTypes.forEach(behavior::validateSignatures);
        ownedRoleRoots = owningRoleRoots(sortedTypes);
        executableDefaultFamilies = CSharpExecutableDefaultRenderModel.mutableFamilies(sortedTypes, hierarchy);
        // Every CLR base signature must admit the null its delegated override can return.
        sortedTypes.stream().filter(CSharpCapabilities::delegatesObjectToString).forEach(type -> {
            for (Type ancestor = type; ancestor != null; ancestor = hierarchy.primaryBase(ancestor)) {
                nullableSummaries.add(ancestor);
            }
        });
        for (Type type : sortedTypes) {
            if (type.isImmutable()) continue;
            var defaults = executableDefaultFamilies.containsKey(type)
                    ? type.getEffectiveProperties().stream().filter(CSharpExecutableDefaultRenderModel::hasDefault)
                            .collect(Collectors.toList())
                    : hierarchy.scalarRedeclarations(type);
            for (Property property : defaults) {
                Type owner = hierarchy.propertyStorageOwner(type, property);
                defaultInitializers.computeIfAbsent(owner, unused -> new HashSet<>()).add(property.getName());
            }
        }
        sharedBehavior = new CSharpSharedBehaviorRenderModel(this, behavior, model);
        if (!sharedBehavior.getBindings().isEmpty() && !emittedTypeNames.add(CSharpSharedBehaviorRenderModel.CONTAINER)) {
            throw invalidName(model.getNamespace(), CSharpSharedBehaviorRenderModel.CONTAINER, "conflicts with a generated name");
        }
        behaviorHierarchy = new CSharpBehaviorHierarchyRenderModel(this, behavior, sortedTypes);
        Set<String> baseTypes = sortedTypes.stream().flatMap(type -> type.getSuperTypes().stream())
                .collect(Collectors.toSet());
        Set<String> boundTypes = sortedTypes.stream().flatMap(type -> type.getTypeParameters().stream())
                .map(TypeParameter::getBound).filter(java.util.Objects::nonNull)
                .map(ValueType::getModelType).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        types = sortedTypes.stream()
                .map(type -> prepareType(type, baseTypes.contains(type.getName()), boundTypes.contains(type.getName())))
                .collect(Collectors.toUnmodifiableList());
        snapshots = new CSharpSnapshotRenderModel(this, sortedTypes, model.polymorphicWireTypeNames());
        reflection = CSharpReflectionRenderModel.prepare(this, sortedTypes);
    }

    String getNamespace() {
        return namespace;
    }

    private static String emittedModelName(Type type) {
        String name = identifier(type.getName(), type.getQualifiedName());
        // A CLR type cannot declare an accessor using its own name. Keep the property
        // name common across inherited interfaces and keep the logical type ID in the model.
        return type.getEffectiveProperties().stream().anyMatch(property -> name.equals(propertyName(type, property)))
                ? name + "Model" : name;
    }

    String getOutputPath() {
        return namespace.replace('.', '/') + "/Model.g.cs";
    }

    List<Map<String, Object>> getTypes() {
        return types;
    }

    CSharpSnapshotRenderModel getSnapshots() {
        return snapshots;
    }

    List<Map<String, Object>> getReflection() {
        return reflection;
    }

    private Set<Type> owningRoleRoots(List<Type> types) {
        Set<String> roles = types.stream().flatMap(type -> type.getEffectiveProperties().stream())
                .filter(property -> property.getKind() == Property.Kind.CONTAINMENT && usesGetterContract(property.getValueType()))
                .map(property -> property.getValueType().getModelType()).collect(Collectors.toSet());
        if (roles.isEmpty()) return Set.of();
        Set<Type> roots = new HashSet<>();
        for (Type type : types) {
            if (type.isImmutable() || type.isInterfaceOnly() || roles.stream().noneMatch(role -> inherits(type, role))) continue;
            Type root = type;
            for (Type parent = hierarchy.primaryBase(type); parent != null; parent = hierarchy.primaryBase(parent)) {
                root = parent;
            }
            roots.add(root);
        }
        return roots;
    }


    private Map<String, Object> prepareType(Type type, boolean hasSubtypes, boolean usedAsBound) {
        String name = typeNames.get(type.getName());
        Set<String> members = new HashSet<>(GENERATED_MEMBERS);
        members.addAll(INHERITED_MEMBERS);
        if (!type.isImmutable()) members.add("__vmfPropertyChangedName");
        members.add(name);

        List<String> parameters = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        for (var parameter : type.getTypeParameters()) {
            String parameterName = identifier(parameter.getName(), type.getQualifiedName());
            if (typeNames.containsValue(parameterName) || !members.add(parameterName)) {
                throw invalidName(type.getQualifiedName(), parameterName, "conflicts with a C# type or member");
            }
            parameters.add(parameterName);
            ValueType upperBound = parameter.getBound();
            if (upperBound == null) continue;
            String bound = parameter.isNumberBound() ? "struct"
                    : (hierarchy.contains(upperBound.getModelType()) || usesGetterContract(upperBound) ? "class, " : "")
                            + typeName(upperBound);
            constraints.add("where " + parameterName + " : " + bound);
        }
        for (Property property : type.getEffectiveProperties()) {
            String member = propertyName(type, property);
            if (!members.add(member)) {
                throw invalidName(type.getQualifiedName() + "." + property.getName(), member,
                        "conflicts with another C# member");
            }
        }
        for (Property property : type.getEffectiveProperties()) {
            property.getEntryKey().ifPresent(key -> {
                String lookup = CSharpKeyedRenderModel.lookupName(type, property, key);
                if (!members.add(lookup)) {
                    throw invalidName(type.getQualifiedName() + "." + property.getName(), lookup,
                            "conflicts with another C# member");
                }
            });
        }
        if (!type.isImmutable()) {
            for (Property property : type.getEffectiveProperties()) {
                String member = propertyName(type, property);
                if (isObservedProperty(property) || sharesShallowSequence(property)) {
                    String storage = valueStorage(member);
                    if (isReciprocal(property)) storage = reciprocalStorage(member);
                    if (!members.add(storage)) {
                        throw invalidName(type.getQualifiedName(), storage, "conflicts with a property backing field");
                    }
                }
                if (sharesShallowSequence(property) && !members.add(shallowShareMethod(member))) {
                    throw invalidName(type.getQualifiedName(), shallowShareMethod(member),
                            "conflicts with a shallow-copy method");
                }
                if (sharesShallowSequence(property) && property.getKind() == Property.Kind.REFERENCE) {
                    String storage = CSharpReadOnlyRenderModel.shallowViewStorage(member);
                    if (!members.add(storage)) {
                        throw invalidName(type.getQualifiedName(), storage, "conflicts with a read-only backing field");
                    }
                }
            }
            for (Type owner = type; owner != null; owner = hierarchy.primaryBase(owner)) {
                for (String property : defaultInitializers.getOrDefault(owner, Set.of())) {
                    String nameOfInitializer = defaultInitializer(propertyName(type, property));
                    if (!members.add(nameOfInitializer)) {
                        throw invalidName(type.getQualifiedName(), nameOfInitializer,
                                "conflicts with a scalar default initializer");
                    }
                }
            }
        }

        var storageProperties = hierarchy.storageProperties(type);
        for (Property property : storageProperties) {
            if (property.getKind() == Property.Kind.CONTAINMENT && !property.isCollection()) {
                String storage = containmentStorage(propertyName(type, property));
                if (!members.add(storage)) {
                    throw invalidName(type.getQualifiedName(), storage, "conflicts with a contained property backing field");
                }
            }
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        List<Map<String, String>> constructorAssignments = new ArrayList<>();
        List<Map<String, String>> postHookAssignments = new ArrayList<>();
        List<Map<String, String>> executableDefaults = new ArrayList<>();
        List<Map<String, String>> collectionDefaults = new ArrayList<>();
        List<Map<String, String>> referenceDefaults = new ArrayList<>();
        List<Map<String, String>> keyedLookups = new ArrayList<>();
        boolean immutableOnlyTrait = isImmutableOnlyBehaviorTrait(type);
        boolean inheritedBehavior = !immutableOnlyTrait && behaviorHierarchy.includes(type);
        var binding = type.isImmutable() || inheritedBehavior || immutableOnlyTrait
                ? Map.of() : behavior.prepare(type, members);
        var hierarchicalBinding = inheritedBehavior ? behaviorHierarchy.prepare(type, members, hasSubtypes) : Map.of();
        var immutableBinding = immutableBehavior.prepare(type, name, members);
        boolean effectiveDefaults = executableDefaultFamilies.containsKey(type);
        boolean deferredDefaults = effectiveDefaults || inheritedBehavior && behaviorHierarchy.defersDefaults(type);
        boolean delayFieldDefaults = deferredDefaults || Boolean.TRUE.equals(binding.get("hasHook"));
        String declarationName = withTypeArguments(name, parameters);
        for (Property property : storageProperties) {
            String member = propertyName(type, property);
            Map<String, Object> rendered = prepareMember(declarationName, member, property, delayFieldDefaults);
            if (hierarchy.implementsRelationshipGetter(type, property) || hierarchy.implementsObjectAlternativeGetter(type, property)) {
                var anchored = new LinkedHashMap<>(rendered);
                anchored.put("hidesRelationshipGetter", true);
                rendered = Map.copyOf(anchored);
            }
            if (!type.isImmutable() && !property.isCollection()) {
                var accessor = new LinkedHashMap<>(rendered);
                boolean boxedObject = CSharpCapabilities.isObjectGetter(property) && isUnboundObject(property.getValueType());
                accessor.put("setter", boxedObject || hierarchy.hasProtectedSetter(type, property) ? "protected set" : "set");
                String setterInput = "value";
                if (boxedObject) {
                    String guard = boxedGuard(type, member, members);
                    accessor.put("boxedGuard", guard);
                    setterInput = guard + "(value)";
                }
                accessor.put("setterInput", setterInput);
                if (defaultInitializers.getOrDefault(type, Set.of()).contains(property.getName())) {
                    accessor.put("defaultInitializer", defaultInitializer(member));
                }
                rendered = Map.copyOf(accessor);
            }
            if (!type.isImmutable() && sharesShallowSequence(property)) {
                var lazy = new java.util.LinkedHashMap<>(rendered);
                lazy.put("initialized", false);
                lazy.put("shareable", true);
                lazy.put("storage", valueStorage(member));
                lazy.put("shareMethod", shallowShareMethod(member));
                lazy.put("shareOwner", declarationName);
                lazy.put("shareSource", changeSourceExpression(property, declarationName, member));
                rendered = Map.copyOf(lazy);
            }
            boolean ownsKeyedSequence = hierarchy.promotesKeyedSequence(type, property.getName());
            boolean exposesSequence = hierarchy.exposesKeyedSequenceBase(type, property.getName());
            if (ownsKeyedSequence || exposesSequence) {
                String getter = "__vmfSequence" + member;
                if (!members.add(getter)) {
                    throw invalidName(type.getQualifiedName(), getter, "conflicts with a keyed sequence accessor");
                }
                var sequence = new LinkedHashMap<>(rendered);
                sequence.put(ownsKeyedSequence ? "promotedSequence" : "sequenceBase", true);
                sequence.put("sequenceType", "global::Vmf.Runtime.IModelList<" + typeName(property.getValueType()) + "?>");
                sequence.put("sequenceGetter", getter);
                rendered = Map.copyOf(sequence);
            }
            properties.add(rendered);
            property.getEntryKey().ifPresent(key -> keyedLookups.add(CSharpKeyedRenderModel.lookup(this, type, property, key)));
            if (property.getKind() == Property.Kind.CONTAINMENT) {
                constructorAssignments.add(Map.of("name", (String) rendered.get("storage"),
                        "value", containmentInitializer(declarationName, member, property)));
            } else if (Boolean.TRUE.equals(rendered.get("initialized"))) {
                constructorAssignments.add(Map.of("name", (String) rendered.getOrDefault("storage", member),
                        "value", (String) rendered.get("initializer")));
            }
            if (CSharpCapabilities.hasReferenceSetDefault(property)) {
                var method = CSharpReferenceDefaultRenderModel.prepare(this, type, property, members);
                referenceDefaults.add(method);
                if (!type.isImmutable()) {
                    postHookAssignments.add(CSharpReferenceDefaultRenderModel.assignment(type, property));
                }
            } else if (CSharpCapabilities.hasManagedCollectionDefault(property)) {
                var method = CSharpCollectionDefaultRenderModel.method(this, type, property, members);
                collectionDefaults.add(method);
                if (!type.isImmutable()) {
                    postHookAssignments.add(Map.of("call", CSharpCollectionDefaultRenderModel.initialize(property, member,
                            method.get("values"))));
                }
            } else if (!effectiveDefaults && CSharpCapabilities.bindsExecutableDefault(property)) {
                var method = CSharpExecutableDefaultRenderModel.method(this, type, property, members,
                        CSharpExecutableDefaultRenderModel.hidesAccessor(type, property, hierarchy));
                executableDefaults.add(method);
                if (!type.isImmutable()) {
                    postHookAssignments.add(Map.of("name", (String) rendered.get("storage"),
                            "value", method.get("accessor") + "()"));
                }
            } else if (!effectiveDefaults && delayFieldDefaults) {
                String storage = (String) rendered.get("storage");
                property.getScalarDefault().ifPresent(value -> postHookAssignments.add(
                        Map.of("name", storage, "value", CSharpDefaultRenderModel.literal(value))));
            }
        }

        var relationshipGetters = type.getProperties().stream()
                .filter(property -> hierarchy.declaresRelationshipGetter(type, property) || hierarchy.declaresObjectAlternativeGetter(type, property))
                .map(property -> projectedGetter(type, property, members, hierarchy.declaresObjectAlternativeGetter(type, property)))
                .collect(Collectors.toUnmodifiableList());
        var relationshipImplementations = storageProperties.stream()
                .filter(property -> hierarchy.implementsRelationshipGetter(type, property) || hierarchy.implementsObjectAlternativeGetter(type, property))
                .map(property -> projectedGetter(type, property, members, hierarchy.implementsObjectAlternativeGetter(type, property)))
                .collect(Collectors.toUnmodifiableList());
        Type base = hierarchy.primaryBase(type);
        boolean hasBase = base != null;
        String parent = hierarchy.bases(type);
        boolean immutableType = type.isImmutable();
        var builderReferenceDefaults = immutableType ? List.<Map<String, String>>of()
                : type.getEffectiveProperties().stream().filter(CSharpCapabilities::hasReferenceSetDefault)
                    .map(property -> CSharpReferenceDefaultRenderModel.builderCache(this, type, property))
                    .collect(Collectors.toUnmodifiableList());
        boolean inheritsReferenceDefaults = !builderReferenceDefaults.isEmpty() && hasBase
                && base.getEffectiveProperties().stream().anyMatch(CSharpCapabilities::hasReferenceSetDefault);
        boolean deferMixedDefaults = executableDefaultFamilies.get(type)
                == CSharpExecutableDefaultRenderModel.DeferredConstructor.MIXED;
        String mixedBaseConstructor = hasBase && deferMixedDefaults ? " : base(initializeReferenceDefaults, true)"
                : inheritsReferenceDefaults ? " : base(initializeReferenceDefaults)" : "";
        var redeclarations = hierarchy.scalarRedeclarations(type);
        var defaults = immutableType ? List.of() : redeclarations.stream().map(property ->
                defaultInitializer(propertyName(type, property)) + "(" + property.getScalarDefault()
                        .map(CSharpDefaultRenderModel::literal).orElse("default") + ");")
                .collect(Collectors.toUnmodifiableList());
        if (effectiveDefaults || immutableType) {
            var bindingDeclarations = effectiveDefaults
                    ? Stream.concat(type.getProperties().stream(), storageProperties.stream()
                        .filter(CSharpCapabilities::bindsExecutableDefault)
                        .filter(property -> !projectedDefaultCall(type, property).isEmpty()))
                    : redeclarations.stream();
            bindingDeclarations.filter(CSharpCapabilities::bindsExecutableDefault).map(property ->
                    CSharpExecutableDefaultRenderModel.method(this, type, property, members,
                            CSharpExecutableDefaultRenderModel.hidesAccessor(type, property, hierarchy)))
                    .forEach(executableDefaults::add);
        }
        if (deferMixedDefaults) {
            type.getProperties().stream().filter(CSharpCapabilities::hasManagedCollectionDefault)
                    .filter(property -> hasInheritedManagedCollectionDefault(type, property))
                    .filter(property -> storageProperties.stream().noneMatch(storage -> storage.getName().equals(property.getName())))
                    .map(property -> CSharpCollectionDefaultRenderModel.method(this, type, property, members))
                    .forEach(collectionDefaults::add);
            postHookAssignments.clear();
            postHookAssignments.addAll(mixedDefaultAssignments(type));
            defaults = List.of();
        } else if (effectiveDefaults) {
            defaults = type.getEffectiveProperties().stream().filter(CSharpExecutableDefaultRenderModel::hasDefault)
                    .map(property -> effectiveScalarDefaultCall(type, property) + ";").collect(Collectors.toUnmodifiableList());
            if (!inheritedBehavior && !members.add("__vmfApplyDeclaredDefaults")) {
                throw invalidName(type.getQualifiedName(), "__vmfApplyDeclaredDefaults", "conflicts with a default initializer");
            }
        }
        var covariant = hierarchy.covariantRedeclarations(type);
        var covariantNames = covariant.stream().map(Property::getName).collect(Collectors.toSet());
        var promotions = (immutableType ? covariant : hierarchy.promotedSetters(type))
                .stream().map(property -> forwardedProperty(type, property, covariantNames.contains(property.getName()), members))
                .collect(Collectors.toUnmodifiableList());
        if (type.isStructuralEquality()) {
            parent += (parent.isEmpty() ? " : " : ", ") + "global::System.IEquatable<" + declarationName + ">";
        }
        if (Boolean.TRUE.equals(immutableBinding.get("hasCalls"))) {
            parent += (parent.isEmpty() ? " : " : ", ") + immutableBinding.get("calls");
        }
        // A CLR class used as a generic constraint must be unsealed, even with no modeled subclasses.
        boolean classBound = usedAsBound && (type.isImmutable() || !hierarchy.contains(type.getName()));
        String declarationModifier = type.isInterfaceOnly() ? "abstract " : hasSubtypes || classBound ? "" : "sealed ";
        if (!executableDefaults.isEmpty() || !collectionDefaults.isEmpty()
                || referenceDefaults.stream().anyMatch(value -> !value.get("binding").isEmpty())
                || !binding.isEmpty() || !immutableBinding.isEmpty()
                || Boolean.TRUE.equals(hierarchicalBinding.get("hasFactory"))) declarationModifier += "partial ";
        String validationModifier = hasBase ? "override " : hasSubtypes || type.isInterfaceOnly() ? "virtual " : "";
        var getterTraits = hierarchy.newGetterTraits(type);
        var replayLists = immutableType ? List.<Map<String, String>>of()
                : type.getEffectiveProperties().stream().filter(CSharpRenderModel::hasOrdinaryListReplay)
                    .map(property -> Map.of("modelName", CSharpSnapshotRenderModel.literal(property.getName()),
                            "member", propertyName(type, property))).collect(Collectors.toUnmodifiableList());
        if (!replayLists.isEmpty()) {
            parent += (parent.isEmpty() ? " : " : ", ") + "global::Vmf.Runtime.IModelListReplayAccess";
        }
        var replayMembership = immutableType ? List.<Map<String, String>>of()
                : type.getEffectiveProperties().stream().filter(CSharpRenderModel::hasScalarMembershipReplay)
                    .map(property -> Map.of("modelName", CSharpSnapshotRenderModel.literal(property.getName()),
                            "member", propertyName(type, property),
                            "collectionType", CSharpScalarCollections.mutableType(this, property)))
                    .collect(Collectors.toUnmodifiableList());
        if (!replayMembership.isEmpty()) {
            parent += (parent.isEmpty() ? " : " : ", ") + "global::Vmf.Runtime.IModelScalarCollectionReplayAccess";
        }
        var companionAdapter = hierarchy.adapter(type);
        boolean addsGraphAccess = Boolean.TRUE.equals(companionAdapter.get("addGraphAccess"));
        if (!addsGraphAccess && ownedRoleRoots.contains(type)) {
            parent += (parent.isEmpty() ? " : " : ", ") + "global::Vmf.Runtime.IModelGraphAccess";
            addsGraphAccess = true;
        }
        if (!hasBase) {
            parent += (parent.isEmpty() ? " : " : ", ") + (immutableType
                    ? "global::Vmf.Runtime.IImmutableModel"
                    : "global::System.ComponentModel.INotifyPropertyChanged, global::Vmf.Runtime.IModelReadOnlyProjection"
                        + ", global::Vmf.Runtime.IModelLinkAccess");
        }
        var propertyChangedNames = immutableType ? List.<Map<String, String>>of()
                : type.getEffectiveProperties().stream().filter(property -> !property.isCollection())
                    .map(property -> Map.of("modelName", CSharpSnapshotRenderModel.literal(property.getName()),
                            "member", propertyName(type, property))).collect(Collectors.toUnmodifiableList());
        String propertyChangedVisibility = hasBase ? "protected override "
                : hasSubtypes || type.isInterfaceOnly() ? "protected virtual " : "private ";
        return Map.ofEntries(
                entry("name", name),
                entry("instanceName", declarationName),
                entry("declaration", "public " + declarationModifier + "class " + declarationName + parent),
                entry("constraints", List.copyOf(constraints)),
                entry("hasBase", hasBase),
                entry("companion", hierarchy.companion(type, constraints)),
                entry("companionAdapter", companionAdapter),
                entry("addsGraphAccess", addsGraphAccess),
                entry("introducesGetterTraits", !getterTraits.isEmpty()),
                entry("traitGetters", getterTraits.stream()
                        .flatMap(trait -> readOnly.traitGetters(trait).stream()).collect(Collectors.toUnmodifiableList())),
                entry("isBase", hasSubtypes || type.isInterfaceOnly()),
                entry("immutable", immutableType),
                entry("readOnly", immutableType ? Map.of() : readOnly.prepare(type, declarationName, hasBase,
                        hasSubtypes || type.isInterfaceOnly())),
                entry("constructor", immutableType ? immutable.constructorProperties(type, storageProperties) : List.of()),
                entry("immutableBaseArguments", immutableType && hasBase ? immutable.baseArguments(type, base, redeclarations) : ""),
                entry("contentModifier", hasBase ? "protected override " : hasSubtypes ? "protected virtual " : "private "),
                entry("contentHashModifier", hasBase ? "override " : hasSubtypes ? "virtual " : ""),
                entry("immutableBuilder", immutableType ? immutable.builder(type, declarationName, hasBase) : Map.of()),
                entry("builder", builder.canBuild(type) ? builder.prepare(type, declarationName) : Map.of()),
                entry("behavior", binding),
                entry("immutableBehavior", immutableBinding),
                entry("objectToStringDelegated", CSharpCapabilities.delegatesObjectToString(type)),
                entry("objectEqualityDelegated", CSharpCapabilities.delegatesObjectEquality(type)),
                entry("summaryReturnType", nullableSummaries.contains(type) ? "string?" : "string"),
                entry("hierarchicalBehavior", hierarchicalBinding),
                entry("deferBehaviorDefaults", deferredDefaults),
                entry("applyEffectiveDefaults", effectiveDefaults),
                entry("deferredBaseConstructor", hasBase ? " : base(true)" : ""),
                entry("deferredDefaultsModifier", (hasSubtypes ? "protected " : "private ") + (hasBase ? "new " : "")),
                entry("structuralEquality", type.isStructuralEquality()),
                entry("structuralBase", hasBase && base.isStructuralEquality()),
                entry("equalityFields", immutableType ? immutable.equalityFields(type) : List.of()),
                entry("validationModifier", validationModifier),
                entry("properties", List.copyOf(properties)),
                entry("replayLists", replayLists),
                entry("replayMembership", replayMembership),
                entry("propertyChangedNames", propertyChangedNames),
                entry("propertyChangedVisibility", propertyChangedVisibility),
                entry("keyedLookups", List.copyOf(keyedLookups)),
                entry("constructorAssignments", List.copyOf(constructorAssignments)),
                entry("postHookAssignments", List.copyOf(postHookAssignments)),
                entry("executableDefaults", List.copyOf(executableDefaults)),
                entry("collectionDefaults", List.copyOf(collectionDefaults)),
                entry("referenceDefaults", List.copyOf(referenceDefaults)),
                entry("builderReferenceDefaults", builderReferenceDefaults),
                entry("hasReferenceDefaultShell", !builderReferenceDefaults.isEmpty()),
                entry("deferMixedDefaults", deferMixedDefaults),
                entry("mixedBaseConstructor", mixedBaseConstructor),
                entry("referenceDefaultConstructorVisibility", hasSubtypes ? "protected" : "private"),
                entry("redeclaredDefaults", defaults),
                entry("promotedSetters", promotions),
                entry("relationshipGetters", relationshipGetters),
                entry("relationshipImplementations", relationshipImplementations),
                entry("immutableCovariantGetters", immutableType && !hasBase ? readOnly.covariantBaseGetters(type) : List.of()),
                entry("validationChecks", CSharpValidationRenderModel.checks(type)),
                entry("validationChildren", validationChildren(type)));
    }

    private Map<String, String> projectedGetter(Type owner, Property property, Set<String> members, boolean objectAlternative) {
        String name = propertyName(owner, property);
        String getter = (objectAlternative ? "__vmfProject" : "__vmfRelationship") + name;
        if (!members.add(getter)) {
            throw invalidName(owner.getQualifiedName(), getter, "conflicts with a projection accessor");
        }
        String value = objectAlternative ? "object?" : typeName(property.getValueType()) + "?";
        String type = property.isCollection() ? "global::Vmf.Runtime.IModelList<" + value + ">" : value;
        return Map.of("name", name, "getter", getter, "type", type);
    }

    boolean projectsObjectAlternativeGetter(Type owner, Type parent, Property property) {
        return hierarchy.implementsObjectAlternativeGetter(owner, property) && hierarchy.declaresObjectAlternativeGetter(parent, property);
    }

    boolean projectsKeyedSequenceBase(Type owner, Type parent, Property property) {
        return hierarchy.promotesKeyedSequence(owner, property.getName())
                && hierarchy.exposesKeyedSequenceBase(parent, property.getName());
    }

    private boolean isImmutableOnlyBehaviorTrait(Type type) {
        if (!type.isGetterOnlyTrait()) return false;
        var implementations = model.getTypes().stream().filter(candidate -> !candidate.isInterfaceOnly()
                && inherits(candidate, type.getName())).collect(Collectors.toList());
        return !implementations.isEmpty() && implementations.stream().allMatch(Type::isImmutable);
    }

    private List<Map<String, Object>> validationChildren(Type type) {
        List<Map<String, Object>> children = type.getEffectiveProperties().stream()
                .filter(property -> property.getKind() == Property.Kind.CONTAINMENT)
                .map(property -> Map.<String, Object>of(
                        "name", propertyName(type, property),
                        "modelName", property.getName(),
                        "collection", property.isCollection(),
                        "unordered", isUnorderedContainment(property),
                        "nullableElements", isOrdinaryContainment(property),
                        "childAccess", owningChildAccess(property, "child"),
                        "slotAccess", owningChildAccess(property, "child" + propertyName(type, property))))
                .collect(Collectors.toCollection(ArrayList::new));
        // A stack reverses insertion order. Push the last child first, as in Java VMF traversal.
        Collections.reverse(children);
        return List.copyOf(children);
    }

    private String owningChildAccess(Property property, String expression) {
        var use = property.getValueType();
        return usesGetterContract(use) ? "((global::Vmf.Runtime.IModelGraphAccess)" + expression + ")" : expression;
    }

    CSharpImmutableRenderModel getImmutable() {
        return immutable;
    }

    Type findType(String name) {
        return model.findType(name).orElseThrow();
    }

    List<Map<String, Object>> getSharedBehaviorBindings() { return sharedBehavior.getBindings(); }

    String sharedBehaviorAccessor(Type type, String binding) { return sharedBehavior.accessor(type, binding); }

    boolean usesNamedBehaviorProviders(Type type) {
        return !behaviorProviderNames(type).isEmpty();
    }

    String behaviorProviderMember(Type owner, String binding, String prefix) {
        var names = behaviorProviderNames(owner);
        if (names.isEmpty()) return prefix;
        String resolved = names.getOrDefault(binding, CSharpDefaultBehaviorNames.simpleName(binding));
        return identifier(prefix + "_" + resolved, owner.getQualifiedName());
    }

    private Map<String, String> behaviorProviderNames(Type owner) {
        return behaviorProviderNames.computeIfAbsent(owner, type -> {
            if (!hasDirectBoundBehaviorRoles(type)) return Map.of();
            var bindings = CSharpDefaultBehaviorNames.declaredBindings(type);
            return bindings.size() < 2 ? Map.of()
                    : CSharpDefaultBehaviorNames.disambiguate(bindings, CSharpDefaultBehaviorNames::simpleName);
        });
    }

    boolean isBoundBehaviorRole(Type type) {
        return CSharpCapabilities.isBoundBehaviorRole(model, type);
    }

    boolean hasDirectBoundBehaviorRoles(Type type) {
        return CSharpCapabilities.hasDirectBoundBehaviorRoles(model, type);
    }

    boolean hasBoundBehaviorBranches(Type type) {
        return CSharpCapabilities.hasBoundBehaviorBranches(model, type);
    }

    boolean isReusableBehaviorComposition(Type type) {
        return behaviorHierarchy.isReusableComposition(type);
    }

    boolean isCompanionType(Type type) {
        return hierarchy.contains(type.getName());
    }

    Set<CallableMethod> companionCallables(Type type) {
        return hierarchy.companionCallables(type).keySet();
    }

    String companionMethod(Type type, CallableMethod method) {
        String name = identifier(method.getName(), type.getQualifiedName());
        var rendered = behavior.prepareMethod(type, method, name);
        return rendered.get("returnType") + " " + name + "(" + rendered.get("parameters") + ");";
    }

    Set<String> multiplyInheritedNames(Type type) {
        return hierarchy.multiplyInheritedNames(type);
    }

    Map<String, Type> builderSourceApplications(Type type) {
        return hierarchy.builderSourceApplications(type);
    }

    boolean hasGetterTraitBuilderStorage(Type owner, Property property) {
        return hierarchy.hasGetterTraitBuilderStorage(owner, property);
    }

    boolean promotesBuilderSetter(Type owner, Property property) {
        return hierarchy.promotedSetters(owner).stream().anyMatch(promoted -> promoted.getName().equals(property.getName()));
    }

    Set<String> inheritedNames(Type type) {
        return hierarchy.inheritedNames(type);
    }

    private Map<String, String> forwardedProperty(Type owner, Property property, boolean covariant, Set<String> members) {
        String name = propertyName(owner, property);
        String valueType = typeName(property.getValueType());
        String type = valueType + (property.getValueType().isPrimitive() ? "" : "?");
        String getter = (covariant ? "(" + type + ")" : "") + "base." + name;
        var member = new LinkedHashMap<>(Map.of("name", name, "type", type, "getter", getter,
                "setter", owner.isInterfaceOnly() ? "protected set" : "set"));
        if (hierarchy.narrowsObjectGetter(owner, property)) {
            member.put("boxedGuard", boxedGuard(owner, name, members));
            member.put("boxedValueType", valueType);
        }
        return Map.copyOf(member);
    }

    boolean isUnboundObject(ValueType value) {
        return Object.class.getName().equals(value.getExternalType()) && model.externalAnnotations(value.getExternalType()).isEmpty();
    }

    private static String boxedGuard(Type owner, String member, Set<String> members) {
        String guard = "__vmfCheck" + member;
        if (!members.add(guard)) {
            throw invalidName(owner.getQualifiedName(), guard, "conflicts with a boxed scalar accessor");
        }
        return guard;
    }

    private static String defaultInitializer(String member) {
        return "__vmfInitialize" + member;
    }

    private List<Map<String, String>> mixedDefaultAssignments(Type type) {
        boolean staged = stagesExecutableCollectionDefaults(type);
        var assignments = new ArrayList<Map<String, String>>();
        for (Property property : type.getEffectiveProperties()) {
            if (CSharpCapabilities.hasReferenceSetDefault(property)) {
                assignments.add(CSharpReferenceDefaultRenderModel.assignment(type, property));
            } else if (CSharpCapabilities.hasManagedCollectionDefault(property)) {
                String name = propertyName(type, property);
                String values = CSharpCollectionDefaultRenderModel.evaluate(name);
                assignments.add(Map.of("call", staged ? CSharpCollectionDefaultRenderModel.initialize(property, name, values)
                        : CSharpCollectionDefaultRenderModel.copyInto(name, values)));
            } else if (CSharpExecutableDefaultRenderModel.hasDefault(property)) {
                assignments.add(Map.of("call", effectiveScalarDefaultCall(type, property)));
            }
        }
        return List.copyOf(assignments);
    }

    private String effectiveScalarDefaultCall(Type type, Property property) {
        String name = propertyName(type, property);
        String value = property.getScalarDefault().map(CSharpDefaultRenderModel::literal)
                .orElseGet(() -> CSharpExecutableDefaultRenderModel.accessor(name) + "()");
        return defaultInitializer(name) + "(" + value + ")";
    }

    boolean stagesExecutableCollectionDefaults(Type type) {
        return CSharpCapabilities.stagesExecutableCollectionDefaults(model, type);
    }

    boolean hasInheritedManagedCollectionDefault(Type owner, Property property) {
        for (Type parent = hierarchy.primaryBase(owner); parent != null; parent = hierarchy.primaryBase(parent)) {
            if (parent.getProperties().stream().anyMatch(candidate -> candidate.getName().equals(property.getName())
                    && CSharpCapabilities.hasManagedCollectionDefault(candidate))) return true;
        }
        return false;
    }

    boolean inheritsGetterTraitDefault(Type owner, Property property) {
        return owner.isImmutable() && CSharpCapabilities.bindsExecutableDefault(property)
                && !owner.getName().equals(property.getDeclaringType()) && findType(property.getDeclaringType()).isGetterOnlyTrait();
    }

    String projectedDefaultCall(Type storage, Property property) {
        if (storage.getName().equals(property.getDeclaringType())
                || storage.isImmutable() && !inheritsGetterTraitDefault(storage, property)) return "";
        for (Type type = hierarchy.primaryBase(storage); type != null; type = hierarchy.primaryBase(type)) {
            if (type.getName().equals(property.getDeclaringType())) return "";
        }
        Type origin = findType(property.getDeclaringType());
        String accessor = CSharpExecutableDefaultRenderModel.accessor(propertyName(origin, property));
        return "global::" + namespace + "." + typeName(origin) + "." + accessor + "()";
    }

    boolean usesGetterContract(ValueType value) {
        return value.getModelType() != null && findType(value.getModelType()).isGetterOnlyTrait();
    }

    private Map<String, Object> prepareMember(String ownerType, String name, Property property, boolean runsHook) {
        if (CSharpScalarCollections.isScalarCollection(property) || usesScalarListForImmutableValues(property)) {
            return CSharpScalarCollections.mutableMember(this, ownerType, property, name);
        }
        String target = propertyValueTypeName(property);
        switch (property.getKind()) {
            case CONTAINMENT:
                boolean collection = property.isCollection();
                String storage = collection ? name : containmentStorage(name);
                String implementation = isUnorderedContainment(property) ? "ContainmentSet"
                        : collection ? "ContainmentList" : "ContainmentSlot";
                String element = target + (isOrdinaryContainment(property) ? "?" : "");
                return Map.of("name", name, "containment", true, "container", false,
                        "initialized", false, "singular", !collection, "storage", storage,
                        "valueType", target, "type", "global::Vmf.Runtime." + implementation + "<"
                                + ownerType + ", " + element + ">");
            case CONTAINER:
                return prepareContainer(target, name, property);
            case REFERENCE:
                if (property.isCollection()) {
                    String initializer = "new(" + collectionChangeSource(property, ownerType, name)
                            + (isReciprocal(property) ? ", opposite: " + oppositeAccessor(property) : "") + ")";
                    return Map.of("name", name, "containment", false, "container", false,
                            "initialized", true, "initializer", initializer,
                            "type", "global::Vmf.Runtime." + referenceCollectionName(property)
                                    + "<" + target + ">");
                }
                if (isReciprocal(property)) {
                    String initializer = "new(" + changeSourceExpression(property, ownerType, name)
                            + ", opposite: " + oppositeAccessor(property) + ")";
                    return Map.of("name", name, "containment", false, "container", false, "reciprocal", true,
                            "initialized", true, "initializer", initializer, "storage", reciprocalStorage(name),
                            "valueType", target, "type", "global::Vmf.Runtime.ReciprocalReference<" + target + ">");
                }
                return observedMember(ownerType, name, target + "?", property, runsHook);
            case SCALAR:
                if (!property.getValueType().isPrimitive()) {
                    target += "?";
                }
                return observedMember(ownerType, name, target, property, runsHook);
            default:
                throw new IllegalStateException("Unsupported property kind " + property.getKind());
        }
    }

    private Map<String, Object> prepareContainer(String target, String name, Property property) {
        if (property.isSharedContainer()) {
            return Map.of("name", name, "containment", false, "container", true, "initialized", false, "type", target,
                    "readOnlyInverse", true,
                    "oppositeCheck", "__vmfContainerLink.HasOpposite(" + CSharpSnapshotRenderModel.literal(property.getName()) + ")");
        }
        Type targetType = findType(property.getValueType().getModelType());
        Property opposite = targetType.getEffectiveProperties().stream()
                .filter(candidate -> candidate.getName().equals(property.getOppositeName())).findFirst().orElseThrow();
        String oppositeName = propertyName(targetType, opposite);
        String owner = "owner." + oppositeName;
        String replacement = "value." + oppositeName;
        String endpoint = "this";
        Type declaring = findType(property.getDeclaringType());
        String accepted = opposite.getValueType().getModelType();
        if (!inherits(declaring, accepted)) {
            endpoint = "this is " + typeName(opposite.getValueType())
                    + " member ? member : throw new InvalidOperationException(\"The container does not admit this model type.\")";
        }
        boolean shared = CSharpCapabilities.usesAlternateOwningField(model, property);
        var writableEnds = declaring.getEffectiveProperties().stream()
                .filter(other -> other.getKind() == Property.Kind.CONTAINER && !other.isGetterOnly())
                .collect(Collectors.toList());
        // Java clears the active link through any writable inverse in a projected ownership family.
        boolean detachThroughAnyEnd = writableEnds.size() > 1 && writableEnds.stream().anyMatch(this::hasProjectedOwner);
        return Map.of("name", name, "containment", false, "container", true, "initialized", false, "type", target,
                "skipSameOwner", shared || detachThroughAnyEnd,
                "skipSameOwnerCheck", (detachThroughAnyEnd ? "value is not null && " : "")
                        + "ReferenceEquals(value, " + name + ")",
                "detachCheck", detachThroughAnyEnd ? "__vmfContainerLink.Owner is not null" : name + " is not null",
                "oppositeCheck", !shared && opposite.isCollection() && !isUnorderedContainment(opposite)
                        ? "ReferenceEquals(__vmfContainerLink.Collection, " + owner + ")"
                        : "__vmfContainerLink.HasOpposite(" + CSharpSnapshotRenderModel.literal(property.getName()) + ")",
                "attach", replacement + (opposite.isCollection() ? ".Add(" + endpoint + ")" : " = " + endpoint));
    }

    private boolean hasProjectedOwner(Property inverse) {
        String ownerName = inverse.getValueType().getModelType();
        if (ownerName == null) return false;
        Type owner = findType(ownerName);
        return owner.getEffectiveProperties().stream().anyMatch(property ->
                property.getName().equals(inverse.getOppositeName()) && hierarchy.implementsRelationshipGetter(owner, property));
    }

    private boolean inherits(Type type, String accepted) {
        return type.getName().equals(accepted)
                || type.getSuperTypes().stream().anyMatch(parent -> inherits(findType(parent), accepted));
    }

    private static String containmentStorage(String name) {
        return "__vmfContainment" + name;
    }

    static boolean isReciprocal(Property property) {
        return property.getKind() == Property.Kind.REFERENCE && !property.getOppositeName().isEmpty();
    }

    private static boolean hasOrdinaryListReplay(Property property) {
        return property.isCollection() && !property.isUnique()
                && (property.getKind() == Property.Kind.SCALAR || property.getKind() == Property.Kind.REFERENCE
                        || isOrdinaryContainment(property));
    }

    private static boolean hasScalarMembershipReplay(Property property) {
        if (!property.getValueType().isPortableScalar()) return false;
        return property.getKind() == Property.Kind.MAP ? property.getKeyType().isPortableScalar()
                : property.getKind() == Property.Kind.SCALAR && property.isCollection() && !property.isOrdered();
    }

    static boolean isOrdinaryContainment(Property property) {
        return property.getKind() == Property.Kind.CONTAINMENT && property.isCollection()
                && !property.isUnique() && property.getEntryKey().isEmpty();
    }

    static boolean isUnorderedContainment(Property property) {
        return property.getKind() == Property.Kind.CONTAINMENT && property.isCollection() && !property.isOrdered();
    }

    static String reciprocalEndpoint(Property property, String name) {
        return (property.isCollection() ? name : reciprocalStorage(name)) + ".Endpoint";
    }

    static String reciprocalStorage(String name) {
        return "__vmfReciprocal" + name;
    }

    String propertyValueTypeName(Property property) {
        var value = property.getValueType();
        return isReciprocal(property) && usesGetterContract(value) ? typeName(findType(value.getModelType()))
                : typeName(value);
    }

    boolean hasGetterRoleReciprocalSetter(Type role, Property inverse) {
        if (!role.isGetterOnlyTrait() || !isReciprocal(inverse)) return false;
        return findType(inverse.getValueType().getModelType()).getEffectiveProperties().stream()
                .anyMatch(edge -> isReciprocal(edge) && edge.getName().equals(inverse.getOppositeName())
                        && role.getName().equals(edge.getValueType().getModelType()));
    }

    private String oppositeAccessor(Property property) {
        Type target = findType(property.getValueType().getModelType());
        Property opposite = target.getEffectiveProperties().stream()
                .filter(candidate -> candidate.getName().equals(property.getOppositeName())).findFirst().orElseThrow();
        String name = propertyName(target, opposite);
        String accessor = hierarchy.contains(target.getName()) && !opposite.isCollection()
                ? reciprocalStorage(name) : reciprocalEndpoint(opposite, name);
        return guardEndpoint(property, opposite, "target", "target." + accessor);
    }

    private String guardEndpoint(Property property, Property opposite, String parameter, String access) {
        String accepted = opposite.getValueType().getModelType();
        if (inherits(findType(property.getDeclaringType()), accepted)) {
            return "static " + parameter + " => " + access;
        }
        return parameter + " => (object)this is " + typeName(opposite.getValueType()) + " ? " + access
                + " : throw new InvalidOperationException(\"This model type is outside the reciprocal relationship.\")";
    }

    private String containmentInitializer(String ownerType, String name, Property property) {
        String opposite = property.getOppositeName().isEmpty() ? "null"
                : CSharpSnapshotRenderModel.literal(property.getOppositeName());
        String metadata = property.getEntryKey().map(key -> ", oppositeName: " + opposite
                + ", keyPropertyName: " + CSharpSnapshotRenderModel.literal(key.getPropertyName())
                + ", keySelector: " + CSharpKeyedRenderModel.selector(this, property, key)
                + ", keyUsesIdentity: " + key.usesIdentity()
                + (isUnorderedContainment(property) ? "" : ", orderedSet: " + property.isUnique()))
                .orElseGet(() -> opposite.equals("null") ? "" : ", oppositeName: " + opposite);
        boolean ordinary = isOrdinaryContainment(property);
        if (ordinary) metadata = ", oppositeName: " + opposite + ", keyPropertyName: null, keySelector: null, orderedSet: false";
        String childLink = hierarchy.contains(property.getValueType().getModelType())
                ? "VmfContainmentLink" : "__vmfContainerLink";
        String childAccess = "child" + (ordinary ? "!" : "") + "." + childLink;
        String linkAccessor = "static child => " + childAccess;
        if (usesGetterContract(property.getValueType())) {
            linkAccessor = "static child => child is global::Vmf.Runtime.IModelGraphAccess item ? item.VmfContainmentLink"
                    + " : throw new global::System.ArgumentException(\"An owning role requires a mutable model.\", nameof(child))";
        } else if (!property.getOppositeName().isEmpty()) {
            Type childType = findType(property.getValueType().getModelType());
            Property inverse = childType.getEffectiveProperties().stream()
                    .filter(candidate -> candidate.getName().equals(property.getOppositeName())).findFirst().orElseThrow();
            linkAccessor = guardEndpoint(property, inverse, "child", childAccess);
        }
        return "new(this, __vmfContainerLink, " + linkAccessor + ", "
                + collectionChangeSource(property, ownerType, name) + metadata + ")";
    }

    static String referenceCollectionName(Property property) {
        if (!property.isUnique()) {
            return "ReferenceList";
        }
        return property.isOrdered() ? "OrderedReferenceSet" : "ReferenceSet";
    }

    String collectionChangeSource(Property property, String ownerType, String name) {
        return "changeSource: " + changeSourceExpression(property, ownerType, name);
    }

    static boolean sharesShallowSequence(Property property) {
        return property.isCollection() && property.isOrdered() && !property.isUnique()
                && (property.getKind() == Property.Kind.SCALAR && property.allowsNullElements()
                    || property.getKind() == Property.Kind.REFERENCE && !isReciprocal(property));
    }

    static String shallowShareMethod(String member) {
        return "__vmfShare" + member;
    }

    private String changeSourceExpression(Property property, String ownerType, String name) {
        String arguments = "this, __vmfContainerLink, " + CSharpSnapshotRenderModel.literal(property.getName());
        if (CSharpScalarCollections.isScalarCollection(property)) {
            arguments += ", static target => target is " + changeOwnerType(ownerType, property)
                    + " owner ? owner." + name + " : null";
        } else if (property.isCollection() && (property.getKind() == Property.Kind.REFERENCE
                || property.getKind() == Property.Kind.CONTAINMENT && (property.isUnique()
                    || property.getEntryKey().isPresent() || isOrdinaryContainment(property)))) {
            if (!property.isUnique() && (property.getKind() == Property.Kind.REFERENCE || isOrdinaryContainment(property))) {
                arguments += ", static target => target is " + ownerType + " owner ? owner." + name + " : null";
            } else {
                arguments += ", bindTarget: null";
            }
            arguments += ", resolveReadOnly: ModelSnapshot.UnwrapReadOnly";
        }
        return "new(" + arguments + ")";
    }

    private static boolean isObservedProperty(Property property) {
        return !property.isCollection() && (property.getKind() == Property.Kind.SCALAR
                || property.getKind() == Property.Kind.REFERENCE);
    }

    private static String valueStorage(String name) {
        return "__vmfValue" + name;
    }

    private Map<String, Object> observedMember(
            String ownerType, String name, String type, Property property, boolean runsHook) {
        return Map.of("name", name, "containment", false, "container", false,
                "initialized", false, "type", type, "ownerType", changeOwnerType(ownerType, property),
                "storage", valueStorage(name),
                "initializer", runsHook ? "" : CSharpDefaultRenderModel.fieldInitializer(property),
                "modelName", CSharpSnapshotRenderModel.literal(property.getName()),
                "reference", needsImmutableArgumentCheck(property) ? immutableArgumentCheck(property)
                        : Boolean.toString(property.getKind() == Property.Kind.REFERENCE));
    }

    private String changeOwnerType(String storageType, Property property) {
        if (property.isGetterOnly() || (property.getKind() != Property.Kind.SCALAR && property.getKind() != Property.Kind.MAP)) {
            return storageType;
        }
        Type declaration = findType(property.getDeclaringType());
        return hierarchy.contains(declaration.getName()) && !declaration.isImmutable()
                && declaration.getTypeParameters().isEmpty() ? hierarchy.companionName(declaration) : storageType;
    }

    String typeName(Type type) {
        return typeNames.get(type.getName());
    }

    CSharpExternalRenderModel.Binding snapshotExternalBinding(ValueType type) {
        return externalTypes.snapshotBinding(type);
    }

    boolean requiresExternalValueCodecs() {
        return externalTypes.requiresCodecs();
    }

    String typeName(ValueType value) {
        switch (value.getKind()) {
            case BOOLEAN:
                return "bool";
            case INT8:
                return "sbyte";
            case INT16:
                return "short";
            case INT32:
                return "int";
            case INT64:
                return "long";
            case FLOAT32:
                return "float";
            case FLOAT64:
                return "double";
            case CHAR:
                return "char";
            case STRING:
                return "string";
            case PARAMETER:
                return identifier(value.getParameterName(), "generic parameter");
            case EXTERNAL:
                return isUnboundObject(value) ? "object" : externalTypes.binding(value).getTypeName();
            case MODEL:
                return withTypeArguments(modelUseName(findType(value.getModelType())),
                        value.getArguments().stream().map(this::typeName)
                        .collect(Collectors.toList()));
            default:
                throw new IllegalStateException("Unsupported C# type " + value.getKind());
        }
    }

    String callableValueTypeName(Type owner, ValueType value) {
        if (value.getArguments().isEmpty()) return typeName(value);
        Type target = findType(value.getModelType());
        return withTypeArguments(modelUseName(target), renderGenericArguments(owner, target, value, this::typeName));
    }

    boolean externalCallableIsValueType(ValueType value) {
        return value.getKind() == ValueType.Kind.EXTERNAL && !isUnboundObject(value)
                && externalTypes.binding(value).isValueType();
    }

    String modelUseName(Type target) {
        if (target.isGetterOnlyTrait()) return readOnly.interfaceName(target);
        return hierarchy.contains(target.getName()) ? hierarchy.companionName(target) : typeName(target);
    }

    boolean usesUnboundedStorageArgument(Property property, boolean key) {
        // Closed and redeclared properties must use the native type of their original storage slot.
        Type owner = findType(property.getDeclaringType());
        Property declaration = owner.getProperties().stream()
                .filter(candidate -> candidate.getName().equals(property.getName())).findFirst().orElse(property);
        for (Type base = hierarchy.primaryBase(owner); base != null; base = hierarchy.primaryBase(base)) {
            var inherited = base.getProperties().stream()
                    .filter(candidate -> candidate.getName().equals(property.getName())).findFirst();
            if (inherited.isPresent()) {
                declaration = inherited.get();
                owner = base;
            }
        }
        ValueType use = key ? declaration.getKeyType() : declaration.getValueType();
        return use != null && use.getKind() == ValueType.Kind.PARAMETER && owner.getTypeParameters().stream()
                .anyMatch(parameter -> parameter.getBound() == null && parameter.getName().equals(use.getParameterName()));
    }

    boolean needsImmutableArgumentCheck(Property property) {
        return property.getKind() == Property.Kind.SCALAR && property.getValueType().getKind() == ValueType.Kind.PARAMETER
                && (!property.isCollection() || property.allowsNullElements()) && usesUnboundedStorageArgument(property, false);
    }

    boolean usesScalarListForImmutableValues(Property property) {
        return property.getKind() == Property.Kind.REFERENCE && property.isCollection()
                && !property.isUnique() && !isReciprocal(property)
                && property.getValueType().getKind() == ValueType.Kind.MODEL
                && findType(property.getValueType().getModelType()).isImmutable()
                && usesUnboundedStorageArgument(property, false);
    }

    boolean needsImmutableArgumentChecks() {
        return model.getTypes().stream().flatMap(type -> type.getProperties().stream()).anyMatch(this::needsImmutableArgumentCheck);
    }

    String immutableArgumentCheck(Property property) {
        return "ModelSnapshot.IsImmutableArgument<" + typeName(property.getValueType()) + ">()";
    }

    static boolean isScalarParameter(TypeParameter parameter) {
        return parameter.isNumberBound() || parameter.getBound() == null;
    }

    List<String> renderGenericArguments(Type context, Type genericType, ValueType application,
                                               Function<ValueType, String> render) {
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < application.getArguments().size(); index++) {
            ValueType value = application.getArguments().get(index);
            String argument = render.apply(value);
            if (genericType.getTypeParameters().get(index).getBound() == null && requiresNullableArgument(context, value)) {
                // An unconstrained C# T? is still int when T is int. Java's boxed Integer needs int? as the argument.
                argument += "?";
            }
            arguments.add(argument);
        }
        return arguments;
    }

    boolean requiresNullableArgument(Type context, ValueType value) {
        switch (value.getKind()) {
            case PARAMETER:
                return context.getTypeParameters().stream().anyMatch(parameter -> parameter.isNumberBound()
                        && parameter.getName().equals(value.getParameterName()));
            case MODEL:
                return false;
            case EXTERNAL:
                return externalTypes.binding(value).isValueType();
            default:
                return true;
        }
    }

    static String withTypeArguments(String name, List<String> arguments) {
        return arguments.isEmpty() ? name : name + "<" + String.join(", ", arguments) + ">";
    }

    static String propertyName(Type owner, Property property) {
        String member = propertyName(owner, property.getName());
        String declaringType = property.getDeclaringType();
        if (isReciprocal(property) && !property.isCollection()
                && property.getName().equals(property.getOppositeName())
                && declaringType.equals(property.getValueType().getModelType())
                && member.equals(identifier(declaringType, owner.getQualifiedName()))) {
            // C# reserves a class name inside that class. Keep the original model and wire identifiers.
            return member + "Reference";
        }
        return member;
    }

    private static String propertyName(Type owner, String name) {
        return identifier(name, owner.getQualifiedName() + "." + name);
    }

    private static String namespacePart(String name, String originalNamespace) {
        String declaration = "namespace " + originalNamespace;
        String part = identifier(name, declaration);
        if (RESERVED_PATH_COMPONENT.matcher(part).matches()) {
            throw invalidName(declaration, part, "is not a portable output directory name");
        }
        return part;
    }

    static String identifier(String name, String declaration) {
        if (!PORTABLE_IDENTIFIER.matcher(name).matches()) {
            throw invalidName(declaration, name,
                    "must be an ASCII letter or underscore followed by letters, digits or underscores");
        }
        String emitted = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        if (RESTRICTED_IDENTIFIERS.contains(emitted)) {
            throw invalidName(declaration, emitted, "is reserved in C#");
        }
        return emitted;
    }

    private static IllegalArgumentException invalidName(String declaration, String name, String reason) {
        return new IllegalArgumentException("Cannot generate C# for " + declaration + ": name '" + name + "' " + reason);
    }
}
