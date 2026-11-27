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
package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/** Lowers secondary Java model supertypes to native C# interfaces over the same storage. */
final class CSharpHierarchyRenderModel {
    private static final String RUNTIME = "global::Vmf.Runtime.";
    private final CSharpRenderModel source;
    private final Set<String> companions = new HashSet<>();
    private final Map<Type, Set<String>> covariantStorage = new HashMap<>();
    private final Map<Type, Set<String>> objectAlternativeGetters = new HashMap<>();
    private final Map<Type, Set<String>> objectAlternativeImplementations = new HashMap<>();
    private final Map<Type, Set<String>> relationshipGetters = new HashMap<>();
    private final Map<Type, Set<String>> relationshipImplementations = new HashMap<>();
    private final Map<Type, Set<String>> keyedSequenceOwners = new HashMap<>();
    private final Map<Type, Set<String>> keyedSequenceBases = new HashMap<>();

    CSharpHierarchyRenderModel(CSharpRenderModel source, ResolvedModel model) {
        this.source = source;
        List<Type> types = model.getTypes();
        for (Type type : types) {
            secondaryApplications(type, primaryApplication(type)).map(ValueType::getModelType).forEach(this::include);
        }
        for (Type type : types) {
            for (Property property : type.getEffectiveProperties()) {
                if (CSharpCapabilities.implementsObjectAlternativeGetter(model, type, property)) {
                    Type base = primaryBase(type);
                    objectAlternativeImplementations.computeIfAbsent(type, unused -> new HashSet<>()).add(property.getName());
                    objectAlternativeGetters.computeIfAbsent(base, unused -> new HashSet<>()).add(property.getName());
                }
                if (CSharpCapabilities.projectsRelationshipGetter(model, type, property)) {
                    Type base = primaryBase(type);
                    relationshipImplementations.computeIfAbsent(type, unused -> new HashSet<>()).add(property.getName());
                    relationshipGetters.computeIfAbsent(base, unused -> new HashSet<>()).add(property.getName());
                }
                if (!CSharpCapabilities.promotesKeyedSequence(model, type, property)) continue;
                Type base = primaryBase(type);
                include(base.getName());
                keyedSequenceOwners.computeIfAbsent(type, unused -> new HashSet<>()).add(property.getName());
                keyedSequenceBases.computeIfAbsent(base, unused -> new HashSet<>()).add(property.getName());
            }
        }
        for (Type type : types) {
            if (type.isImmutable()) continue;
            for (Property property : covariantRedeclarations(type)) {
                Type owner = propertyStorageOwner(type, property);
                covariantStorage.computeIfAbsent(owner, unused -> new HashSet<>()).add(property.getName());
            }
        }
    }

    private void include(String name) {
        if (!source.findType(name).isGetterOnlyTrait() && companions.add(name)) {
            source.findType(name).getSuperTypes().forEach(this::include);
        }
    }

    boolean contains(String name) {
        return companions.contains(name);
    }

    String companionName(Type type) {
        return "I" + source.typeName(type);
    }

    List<Property> storageProperties(Type type) {
        Type base = primaryBase(type);
        Set<String> inherited = base == null ? Set.of() : propertyNames(base.getEffectiveProperties());
        List<Property> slots = type.getSuperTypes().size() < 2 && !type.isImmutable()
                ? type.getProperties() : type.getEffectiveProperties();
        return slots.stream().filter(property -> !declaresRelationshipGetter(type, property)
                        && !declaresObjectAlternativeGetter(type, property)
                        && (!inherited.contains(property.getName()) || implementsRelationshipGetter(type, property)
                            || implementsObjectAlternativeGetter(type, property)
                            || promotesKeyedSequence(type, property.getName())))
                .collect(Collectors.toUnmodifiableList());
    }

    boolean declaresRelationshipGetter(Type type, Property property) {
        return relationshipGetters.getOrDefault(type, Set.of()).contains(property.getName());
    }

    boolean implementsRelationshipGetter(Type type, Property property) {
        return relationshipImplementations.getOrDefault(type, Set.of()).contains(property.getName());
    }

    boolean declaresObjectAlternativeGetter(Type type, Property property) {
        return objectAlternativeGetters.getOrDefault(type, Set.of()).contains(property.getName());
    }

    boolean implementsObjectAlternativeGetter(Type type, Property property) {
        return objectAlternativeImplementations.getOrDefault(type, Set.of()).contains(property.getName());
    }

    boolean promotesKeyedSequence(Type type, String name) {
        return keyedSequenceOwners.getOrDefault(type, Set.of()).contains(name);
    }

    boolean exposesKeyedSequenceBase(Type type, String name) {
        return keyedSequenceBases.getOrDefault(type, Set.of()).contains(name);
    }

    private static Set<String> propertyNames(List<Property> properties) {
        return properties.stream().map(Property::getName).collect(Collectors.toSet());
    }

    Set<String> inheritedNames(Type type) {
        return type.getSuperTypes().stream().map(source::findType)
                .flatMap(parent -> parent.getEffectiveProperties().stream())
                .map(Property::getName).collect(Collectors.toSet());
    }

    List<Property> scalarRedeclarations(Type type) {
        Type base = primaryBase(type);
        if (base == null) return List.of();
        Set<String> inherited = propertyNames(base.getEffectiveProperties());
        return type.getProperties().stream().filter(property -> property.getKind() == Property.Kind.SCALAR
                && !property.isCollection() && inherited.contains(property.getName()) && !narrowsObjectGetter(type, property)
                && !implementsObjectAlternativeGetter(type, property))
                .collect(Collectors.toUnmodifiableList());
    }

    List<Property> covariantRedeclarations(Type type) {
        Type parent = primaryBase(type);
        if (parent == null) return List.of();
        return type.getProperties().stream().filter(property -> !implementsObjectAlternativeGetter(type, property)
                && parent.getEffectiveProperties().stream()
                        .anyMatch(inherited -> narrowsGetter(property, inherited)))
                .collect(Collectors.toUnmodifiableList());
    }

    boolean narrowsObjectGetter(Type type, Property property) {
        Type parent = primaryBase(type);
        return parent != null && parent.getEffectiveProperties().stream()
                .anyMatch(inherited -> CSharpCapabilities.narrowsObjectGetter(property, inherited));
    }

    static boolean narrowsGetter(Property narrow, Property broad) {
        return CSharpCapabilities.narrowsObjectGetter(narrow, broad) || narrowsReference(narrow, broad);
    }

    private static boolean narrowsReference(Property narrow, Property broad) {
        return narrow.getName().equals(broad.getName()) && narrow.getKind() == Property.Kind.REFERENCE
                && broad.getKind() == Property.Kind.REFERENCE && !narrow.isCollection() && !broad.isCollection()
                && narrow.getValueType().getKind() == ValueType.Kind.MODEL
                && broad.getValueType().getKind() == ValueType.Kind.MODEL
                && !narrow.getValueType().getModelType().equals(broad.getValueType().getModelType());
    }

    Type propertyStorageOwner(Type type, Property property) {
        while (type != null) {
            if (storageProperties(type).stream().anyMatch(member -> member.getName().equals(property.getName()))) {
                return type;
            }
            type = primaryBase(type);
        }
        throw new IllegalStateException("Missing storage for " + property.getName());
    }

    boolean hasProtectedSetter(Type type, Property property) {
        return type.isInterfaceOnly() && property.isGetterOnly() && !property.isCollection() && !property.isSharedContainer()
                && (!type.isGetterOnlyTrait() || covariantStorage.getOrDefault(type, Set.of()).contains(property.getName())
                        || source.hasGetterRoleReciprocalSetter(type, property));
    }

    List<Property> promotedSetters(Type type) {
        Set<String> promote = propertyNames(covariantRedeclarations(type));
        if (!type.isInterfaceOnly()) {
            for (Type parent = primaryBase(type); parent != null && parent.isInterfaceOnly(); parent = primaryBase(parent)) {
                for (Property property : storageProperties(parent)) {
                    if (hasProtectedSetter(parent, property)) promote.add(property.getName());
                }
            }
        }
        return type.getEffectiveProperties().stream().filter(property -> promote.contains(property.getName()))
                .collect(Collectors.toUnmodifiableList());
    }

    Set<String> multiplyInheritedNames(Type type) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (String parent : type.getSuperTypes()) {
            for (Property property : source.findType(parent).getEffectiveProperties()) {
                if (!seen.add(property.getName())) duplicates.add(property.getName());
            }
        }
        return duplicates;
    }

    private ValueType primaryApplication(Type type) {
        return type.getSuperTypeApplications().stream()
                .filter(parent -> !type.isImmutable() || source.findType(parent.getModelType()).isImmutable())
                .findFirst().orElse(null);
    }

    private static Stream<ValueType> secondaryApplications(Type type, ValueType primary) {
        return type.getSuperTypeApplications().stream().filter(parent -> parent != primary);
    }

    Type primaryBase(Type type) {
        var primary = primaryApplication(type);
        return primary == null ? null : source.findType(primary.getModelType());
    }

    String bases(Type type) {
        List<String> names = new ArrayList<>();
        var primary = primaryApplication(type);
        if (primary != null) {
            Type base = source.findType(primary.getModelType());
            var arguments = source.renderGenericArguments(type, base, primary, source::typeName);
            names.add(CSharpRenderModel.withTypeArguments(source.typeName(base), arguments));
        }
        if (contains(type.getName())) {
            names.add(link(type, formalArguments(type)).mutableName());
        } else {
            secondaryApplications(type, primary)
                    .map(parent -> argument(type, parent, formalBindings(type)).mutable).forEach(names::add);
        }
        if (type.isGetterOnlyTrait()) names.add("IReadOnly" + source.typeName(type));
        return names.isEmpty() ? "" : " : " + String.join(", ", names);
    }

    List<Type> newGetterTraits(Type type) {
        if (type.isImmutable()) return List.of();
        Set<Type> inherited = new HashSet<>();
        Type base = primaryBase(type);
        if (base != null) collectGetterTraits(base, inherited);
        Set<Type> provided = new LinkedHashSet<>();
        collectGetterTraits(type, provided);
        provided.removeAll(inherited);
        return List.copyOf(provided);
    }

    private void collectGetterTraits(Type type, Set<Type> result) {
        if (type.isGetterOnlyTrait()) result.add(type);
        type.getSuperTypes().stream().map(source::findType).forEach(parent -> collectGetterTraits(parent, result));
    }

    Map<String, Object> companion(Type type, List<String> constraints) {
        if (!contains(type.getName())) return Map.of();
        Link self = link(type, formalArguments(type));
        List<String> parents = type.getSuperTypeApplications().stream()
                .map(value -> argument(type, value, formalBindings(type)).mutable)
                .collect(Collectors.toCollection(ArrayList::new));
        boolean hasCompanionBase = type.getSuperTypes().stream().anyMatch(this::contains);
        if (!hasCompanionBase) parents.add(RUNTIME + (type.isImmutable() ? "IReadOnlyModelView" : "IModelGraphAccess"));
        Set<String> inherited = inheritedNames(type);
        Set<String> duplicates = multiplyInheritedNames(type);
        var declarations = companionProperties(type, duplicates);
        List<String> fields = declarations.stream()
                .map(property -> (inherited.contains(property.getName()) ? "new " : "") + fieldDeclaration(type, property))
                .collect(Collectors.toCollection(ArrayList::new));
        declarations.stream().filter(CSharpHierarchyRenderModel::hasScalarReciprocalEndpoint).forEach(property -> {
            String member = CSharpRenderModel.propertyName(type, property);
            String modifier = inherited.contains(property.getName()) ? "internal new " : "internal ";
            fields.add(modifier + RUNTIME + "ReciprocalEndpoint " + CSharpRenderModel.reciprocalStorage(member) + " { get; }");
        });
        companionCallables(type).forEach((method, hides) ->
                fields.add((hides ? "new " : "") + source.companionMethod(type, method)));
        var lookups = declarations.stream()
                .filter(property -> !inherited.contains(property.getName()) || duplicates.contains(property.getName()))
                .flatMap(property -> property.getEntryKey().stream()
                        .map(key -> CSharpKeyedRenderModel.lookup(source, type, property, key, duplicates.contains(property.getName()))))
                .collect(Collectors.toUnmodifiableList());
        return Map.of("declaration", "public interface " + self.mutableName() + " : " + String.join(", ", parents),
                "constraints", constraints, "fields", List.copyOf(fields), "lookups", lookups,
                "readOnlyType", type.isImmutable() ? self.mutableName() : self.readOnlyName(),
                "hidesProjection", hasCompanionBase);
    }

    Map<CallableMethod, Boolean> companionCallables(Type type) {
        var callables = new LinkedHashMap<CallableMethod, Boolean>();
        if (!contains(type.getName())) return callables;
        if (source.isReusableBehaviorComposition(type) || source.isBoundBehaviorRole(type)) {
            var methodsByBranch = type.getSuperTypes().stream().map(source::findType)
                    .map(Type::getEffectiveMethods).collect(Collectors.toList());
            for (var method : type.getEffectiveMethods()) {
                boolean declared = method.getDeclaringType().equals(type.getName());
                boolean competes = methodsByBranch.stream().filter(methods -> methods.stream()
                        .anyMatch(candidate -> candidate.getName().equals(method.getName()))).count() > 1;
                boolean overloadsLocal = type.getDeclaredMethods().stream()
                        .anyMatch(candidate -> candidate.getName().equals(method.getName()));
                if (!(declared || competes || overloadsLocal)) continue;
                boolean hides = methodsByBranch.stream().flatMap(List::stream)
                        .anyMatch(candidate -> candidate.getSourceSignature().equals(method.getSourceSignature()));
                callables.put(method, hides);
            }
        } else if (CSharpCapabilities.isUnboundBehaviorTrait(type)) {
            type.getDeclaredMethods().forEach(method -> callables.put(method, false));
        }
        return callables;
    }

    Map<String, Type> builderSourceApplications(Type type) {
        Type builderOwner = type;
        Map<String, Type> result = new LinkedHashMap<>();
        var bindings = formalBindings(type);
        Map<String, Link> companionSources = new LinkedHashMap<>();
        for (var application : type.getSuperTypeApplications()) {
            collect(type, application, bindings, companionSources);
        }
        for (var application = primaryApplication(type); application != null; application = primaryApplication(type)) {
            Type parent = source.findType(application.getModelType());
            var arguments = appliedArguments(type, parent, application, bindings);
            String name = CSharpRenderModel.withTypeArguments(source.typeName(parent),
                    arguments.stream().map(item -> item.mutable).collect(Collectors.toList()));
            result.put(name, parent);
            bindings = link(parent, arguments).bindings;
            type = parent;
        }
        companionSources.forEach((name, link) -> result.putIfAbsent(name, link.type));
        if (!builderOwner.isImmutable()) {
            Set<Type> getterTraits = new LinkedHashSet<>();
            collectGetterTraits(builderOwner, getterTraits);
            getterTraits.stream().filter(this::isBuilderGetterTrait)
                    .forEach(trait -> result.putIfAbsent("IReadOnly" + source.typeName(trait), trait));
        }
        return result;
    }

    boolean hasGetterTraitBuilderStorage(Type owner, Property property) {
        if (!isBuilderGetter(property) || !isBuilderGetterTrait(source.findType(property.getDeclaringType()))) return false;
        Type storage = propertyStorageOwner(owner, property);
        return !storage.isImmutable() && !hasProtectedSetter(storage, property);
    }

    private boolean isBuilderGetterTrait(Type type) {
        return type.isGetterOnlyTrait() && !type.getEffectiveProperties().isEmpty()
                && type.getEffectiveProperties().stream().allMatch(this::isBuilderGetter);
    }

    private boolean isBuilderGetter(Property property) {
        if (!property.isGetterOnly()) return false;
        ValueType value = property.getValueType();
        switch (property.getKind()) {
            case SCALAR:
                return value.isPortableScalar();
            case MAP:
                return value.isPortableScalar() && property.getKeyType().isPortableScalar();
            case REFERENCE:
                // An immutable target has the same CLR type and identity on the role view.
                return property.getOppositeName().isEmpty() && value.getKind() == ValueType.Kind.MODEL
                        && value.getArguments().isEmpty() && source.findType(value.getModelType()).isImmutable();
            default:
                return false;
        }
    }

    Map<String, Object> adapter(Type type) {
        Map<String, Link> inherited = new LinkedHashMap<>();
        var primary = primaryApplication(type);
        var bindings = formalBindings(type);
        if (primary != null) collect(type, primary, bindings, inherited);
        Map<String, Link> implementations = new LinkedHashMap<>();
        if (contains(type.getName())) add(link(type, formalArguments(type)), implementations);
        secondaryApplications(type, primary).forEach(parent -> collect(type, parent, bindings, implementations));
        inherited.keySet().forEach(implementations::remove);
        var views = implementations.values().stream()
                .map(impl -> Map.of("name", impl.mutableName(), "view", impl.readOnlyName()))
                .collect(Collectors.toUnmodifiableList());
        List<String> storage = new ArrayList<>();
        for (Link impl : implementations.values()) {
            for (Property property : companionProperties(impl.type, multiplyInheritedNames(impl.type))) {
                if (property.getKind() == Property.Kind.CONTAINMENT && property.isCollection()) {
                    String element = argument(impl.type, property.getValueType(), impl.bindings).mutable;
                    String member = CSharpRenderModel.propertyName(impl.type, property);
                    storage.add(containmentCollectionContract(property, element) + " " + impl.mutableName()
                            + "." + member + " => " + member + ";");
                } else if (exposesKeyedSequenceBase(impl.type, property.getName())) {
                    String element = argument(impl.type, property.getValueType(), impl.bindings).mutable;
                    String member = CSharpRenderModel.propertyName(impl.type, property);
                    storage.add(RUNTIME + "IModelList<" + element + "?> " + impl.mutableName()
                            + "." + member + " => " + member + ";");
                } else if (hasScalarReciprocalEndpoint(property)) {
                    String member = CSharpRenderModel.propertyName(impl.type, property);
                    storage.add(RUNTIME + "ReciprocalEndpoint " + impl.mutableName() + "."
                            + CSharpRenderModel.reciprocalStorage(member) + " => "
                            + CSharpRenderModel.reciprocalEndpoint(property, member) + ";");
                }
            }
        }
        return Map.of("addGraphAccess", inherited.isEmpty() && !implementations.isEmpty(),
                "views", views, "storage", List.copyOf(storage));
    }

    private static boolean hasScalarReciprocalEndpoint(Property property) {
        return CSharpRenderModel.isReciprocal(property) && !property.isCollection();
    }

    private static List<Property> companionProperties(Type type, Set<String> duplicates) {
        Set<String> names = type.getProperties().stream().map(Property::getName).collect(Collectors.toSet());
        names.addAll(duplicates);
        return type.getEffectiveProperties().stream().filter(property -> names.contains(property.getName())
                        || (property.isGetterOnly() && exposesSetter(type, property)))
                .collect(Collectors.toUnmodifiableList());
    }

    private String fieldDeclaration(Type type, Property property) {
        if (type.isImmutable()) {
            return source.getImmutable().propertyType(property) + " " + CSharpRenderModel.propertyName(type, property) + " { get; }";
        }
        String value = source.typeName(property.getValueType());
        String typeName;
        if (CSharpScalarCollections.isScalarCollection(property)) {
            typeName = CSharpScalarCollections.mutableType(source, property);
        } else if (!property.isCollection()) {
            typeName = value + (property.getValueType().isPrimitive() ? "" : "?");
        } else if (property.getKind() == Property.Kind.CONTAINMENT) {
            typeName = containmentCollectionContract(property, value);
        } else if (exposesKeyedSequenceBase(type, property.getName())) {
            typeName = RUNTIME + "IModelList<" + value + "?>";
        } else {
            typeName = RUNTIME + CSharpRenderModel.referenceCollectionName(property) + "<" + value + ">";
        }
        return typeName + " " + CSharpRenderModel.propertyName(type, property)
                + (exposesSetter(type, property) ? " { get; set; }" : " { get; }");
    }

    private static String containmentCollectionContract(Property property, String element) {
        if (CSharpRenderModel.isUnorderedContainment(property)) {
            return RUNTIME + "IContainmentSet<" + element + ">";
        }
        return RUNTIME + "IContainmentCollection<" + element
                + (CSharpRenderModel.isOrdinaryContainment(property) ? "?" : "") + ">";
    }

    private static boolean exposesSetter(Type type, Property property) {
        return !property.isCollection() && (!property.isGetterOnly()
                || !type.isInterfaceOnly() && property.getKind() != Property.Kind.CONTAINER);
    }

    private void collect(Type context, ValueType application, Map<String, Argument> bindings, Map<String, Link> target) {
        Type type = source.findType(application.getModelType());
        add(link(type, appliedArguments(context, type, application, bindings)), target);
    }

    private void add(Link link, Map<String, Link> target) {
        if (contains(link.type.getName()) && target.putIfAbsent(link.mutableName(), link) != null) return;
        for (ValueType parent : link.type.getSuperTypeApplications()) {
            collect(link.type, parent, link.bindings, target);
        }
    }

    private List<Argument> formalArguments(Type type) {
        return type.getTypeParameters().stream()
                .map(parameter -> {
                    String name = CSharpRenderModel.identifier(parameter.getName(), type.getQualifiedName());
                    if (!CSharpRenderModel.isScalarParameter(parameter) && parameter.getBound().getKind() == ValueType.Kind.PARAMETER) {
                        Type bound = source.findType(parameter.getBound().getModelType());
                        return new Argument(name, bound.isImmutable() ? source.typeName(bound) : "IReadOnly" + source.typeName(bound));
                    }
                    return new Argument(name, CSharpRenderModel.isScalarParameter(parameter) ? name
                            : argument(type, parameter.getBound(), Map.of()).view);
                })
                .collect(Collectors.toList());
    }

    private Map<String, Argument> formalBindings(Type type) {
        return link(type, formalArguments(type)).bindings;
    }

    private Link link(Type type, List<Argument> arguments) {
        Map<String, Argument> bindings = new HashMap<>();
        var params = type.getTypeParameters();
        for (int index = 0; index < params.size(); index++) {
            bindings.put(params.get(index).getName(), arguments.get(index));
        }
        return new Link(type, arguments, bindings);
    }

    private List<Argument> appliedArguments(Type context, Type target, ValueType application, Map<String, Argument> bindings) {
        return IntStream.range(0, application.getArguments().size()).mapToObj(index -> {
            ValueType value = application.getArguments().get(index);
            Argument rendered = argument(context, value, bindings);
            if (target.getTypeParameters().get(index).getBound() == null
                    && source.requiresNullableArgument(context, value)) {
                return new Argument(rendered.mutable + "?", rendered.view + "?");
            }
            return rendered;
        }).collect(Collectors.toList());
    }

    private Argument argument(Type context, ValueType value, Map<String, Argument> bindings) {
        if (value.getKind() == ValueType.Kind.PARAMETER) {
            String name = source.typeName(value);
            return bindings.getOrDefault(value.getParameterName(), new Argument(name, name));
        }
        if (value.getKind() != ValueType.Kind.MODEL) {
            String name = source.typeName(value);
            return new Argument(name, name);
        }
        Type target = source.findType(value.getModelType());
        String name = source.typeName(target);
        String viewName = target.isImmutable() ? name : "IReadOnly" + name;
        String mutableName = target.isGetterOnlyTrait() ? viewName : contains(target.getName()) ? companionName(target) : name;
        var arguments = appliedArguments(context, target, value, bindings);
        return new Argument(
                CSharpRenderModel.withTypeArguments(mutableName, arguments.stream().map(item -> item.mutable).collect(Collectors.toList())),
                CSharpRenderModel.withTypeArguments(viewName, arguments.stream().map(item -> item.view).collect(Collectors.toList())));
    }

    private final class Link {
        private final Type type;
        private final List<Argument> arguments;
        private final Map<String, Argument> bindings;

        private Link(Type type, List<Argument> arguments, Map<String, Argument> bindings) {
            this.type = type;
            this.arguments = arguments;
            this.bindings = bindings;
        }

        private String mutableName() {
            return CSharpRenderModel.withTypeArguments(companionName(type),
                    arguments.stream().map(item -> item.mutable).collect(Collectors.toList()));
        }

        private String readOnlyName() {
            var formal = formalArguments(type);
            List<String> projected = IntStream.range(0, arguments.size()).mapToObj(index ->
                    CSharpRenderModel.isScalarParameter(type.getTypeParameters().get(index))
                            ? arguments.get(index).view : formal.get(index).view)
                    .collect(Collectors.toList());
            return CSharpRenderModel.withTypeArguments("IReadOnly" + source.typeName(type), projected);
        }
    }

    private static final class Argument {
        private final String mutable;
        private final String view;

        private Argument(String mutable, String view) {
            this.mutable = mutable;
            this.view = view;
        }
    }
}
