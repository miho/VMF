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
package eu.mihosoft.vmf.typescript;

import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Prepares native TypeScript declarations from the analyzed VMF model. */
final class TypeScriptRenderModel {
    private static final Pattern PORTABLE_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern RESERVED_PATH_COMPONENT = Pattern.compile(
            "(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])");
    private static final Set<String> RESERVED_MEMBERS = Set.of(
            "constructor", "__proto__", "validate", "__vmfValidateInto", "__vmfContainerLink",
            "contentEquals", "contentHashCode", "deepCopy", "vmfChanges", "asReadOnly", "vmfType",
            "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "toLocaleString", "toString", "valueOf");
    private static final String BRAND_PREFIX = "__vmfBrand_";
    private static final String READ_ONLY_PREFIX = "ReadOnly";
    private static final String INTERNAL_PREFIX = "__vmf";

    private final ResolvedModel model;
    private final String outputPath;
    private final Map<String, String> typeNames = new LinkedHashMap<>();
    private final List<Map<String, Object>> types;
    private final List<Map<String, String>> supertypeDeclarations = new ArrayList<>();
    private final List<Map<String, Object>> readOnlyTypes;
    private final List<String> modelTypes;
    private final TypeScriptSnapshotRenderModel snapshots;
    /** Getter-only properties of interface-only types that each subtype declares with its own storage. */
    private final Map<String, Set<String>> abstractSlots = new HashMap<>();
    /** The properties whose storage a class declares or inherits from its base classes. */
    private final Map<String, Set<String>> storage = new HashMap<>();
    /** Concrete types that other classes implement besides their base class; their brand is a public symbol. */
    private final Set<String> symbolBrands = new java.util.TreeSet<>();

    TypeScriptRenderModel(ResolvedModel model) {
        this.model = model;
        outputPath = Arrays.stream(model.getNamespace().split("\\.", -1))
                .map(part -> namespacePart(part, model.getNamespace()))
                .collect(Collectors.joining("/")) + "/model.g.ts";

        Set<String> emittedNames = new HashSet<>();
        emittedNames.add("ModelSnapshot");
        for (Type type : model.getTypes()) {
            String emitted = typeIdentifier(type.getName(), type.getQualifiedName());
            if (!emittedNames.add(emitted)) {
                throw invalidName(type.getQualifiedName(), emitted, "conflicts with another TypeScript declaration");
            }
            typeNames.put(type.getName(), emitted);
        }
        for (Type type : model.getTypes()) {
            String view = READ_ONLY_PREFIX + typeNames.get(type.getName());
            if (!type.isImmutable() && !emittedNames.add(view)) {
                throw invalidName(type.getQualifiedName(), view, "conflicts with the read-only view of another type");
            }
        }

        List<Type> ordered = parentFirst(model);
        for (Type type : ordered) {
            prepareStorage(type);
            for (String secondary : secondarySupertypes(type)) {
                if (!model.findType(secondary).orElseThrow().isInterfaceOnly()) {
                    symbolBrands.add(secondary);
                }
            }
        }
        types = ordered.stream().map(this::prepareType).collect(Collectors.toUnmodifiableList());
        readOnlyTypes = ordered.stream().filter(type -> !type.isImmutable()).map(this::prepareReadOnly)
                .collect(Collectors.toUnmodifiableList());
        modelTypes = ordered.stream().map(this::modelTypeDeclaration).collect(Collectors.toUnmodifiableList());
        snapshots = new TypeScriptSnapshotRenderModel(this, ordered, model.polymorphicWireTypeNames());
    }

    private static List<Type> parentFirst(ResolvedModel model) {
        List<Type> ordered = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        model.getTypes().stream().sorted(Comparator.comparing(Type::getName))
                .forEach(type -> appendType(model, type, visited, ordered));
        return ordered;
    }

    private static void appendType(ResolvedModel model, Type type, Set<String> visited, List<Type> ordered) {
        if (visited.add(type.getName())) {
            for (String parent : type.getSuperTypes()) {
                appendType(model, model.findType(parent).orElseThrow(), visited, ordered);
            }
            ordered.add(type);
        }
    }

    String getOutputPath() {
        return outputPath;
    }

    List<Map<String, Object>> getTypes() {
        return types;
    }

    TypeScriptSnapshotRenderModel getSnapshots() {
        return snapshots;
    }

    /** The read-only view interfaces of the mutable model types and getter-only traits. */
    List<Map<String, Object>> getReadOnlyTypes() {
        return readOnlyTypes;
    }

    /** The reflection declarations of all model types. */
    List<String> getModelTypes() {
        return modelTypes;
    }

    /** Model supertypes a class implements besides its base class, for {@code instanceof}. */
    List<Map<String, String>> getSupertypeDeclarations() {
        return supertypeDeclarations;
    }

    /** The brand symbols of concrete types that other classes implement besides their base class. */
    List<String> getBrandSymbols() {
        return symbolBrands.stream().map(name -> "declare const " + BRAND_PREFIX + typeNames.get(name) + ": unique symbol;")
                .collect(Collectors.toUnmodifiableList());
    }

    private Type primaryType(Type type) {
        ValueType primary = primarySupertype(type);
        return primary == null ? null : model.findType(primary.getModelType()).orElseThrow();
    }

    /** The supertypes of a class that are not on the chain of its base classes. */
    private Set<String> secondarySupertypes(Type type) {
        ValueType primary = primarySupertype(type);
        Set<String> secondary = supertypeClosure(type.getName());
        if (primary != null) {
            secondary.removeAll(supertypeClosure(primary.getModelType()));
            secondary.remove(primary.getModelType());
        }
        return secondary;
    }

    /**
     * Records which properties a class stores. A class stores what its base classes store and declares the rest;
     * an interface-only type leaves a getter-only property abstract when a subtype declares it again with other
     * storage (a narrower reference, or the containment or container end of a relationship), so each subtype
     * declares its own. Getter-only traits store nothing.
     */
    private void prepareStorage(Type type) {
        Type base = primaryType(type);
        Set<String> inherited = base == null ? Set.of() : storage.get(base.getName());
        Set<String> abstracted = new HashSet<>();
        Set<String> stored = new HashSet<>(inherited);
        if (!type.isGetterOnlyTrait()) {
            for (Property property : type.getEffectiveProperties()) {
                if (inherited.contains(property.getName())) continue;
                if (type.isInterfaceOnly() && !type.isImmutable() && property.isGetterOnly()
                        && redeclaredWithOtherStorage(type, property)) {
                    abstracted.add(property.getName());
                } else {
                    stored.add(property.getName());
                }
            }
        } else {
            stored.clear();
        }
        abstractSlots.put(type.getName(), abstracted);
        storage.put(type.getName(), stored);
    }

    private boolean redeclaredWithOtherStorage(Type owner, Property property) {
        for (Type candidate : model.getTypes()) {
            if (candidate == owner || !isSubtype(candidate, owner.getName())) continue;
            for (Property declared : candidate.getProperties()) {
                if (declared.getName().equals(property.getName()) && !sameStorage(declared, property)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean sameStorage(Property first, Property second) {
        return first.getKind() == second.getKind() && first.isCollection() == second.isCollection()
                && first.isOrdered() == second.isOrdered() && first.isUnique() == second.isUnique()
                && first.allowsNullElements() == second.allowsNullElements()
                && first.getOppositeName().equals(second.getOppositeName())
                && sameValueType(first.getValueType(), second.getValueType());
    }

    private static boolean sameValueType(ValueType first, ValueType second) {
        if (first.getKind() != second.getKind() || first.isPrimitive() != second.isPrimitive()
                || !java.util.Objects.equals(first.getModelType(), second.getModelType())
                || !java.util.Objects.equals(first.getExternalType(), second.getExternalType())
                || !java.util.Objects.equals(first.getParameterName(), second.getParameterName())
                || first.getArguments().size() != second.getArguments().size()) {
            return false;
        }
        for (int i = 0; i < first.getArguments().size(); i++) {
            if (!sameValueType(first.getArguments().get(i), second.getArguments().get(i))) return false;
        }
        return true;
    }

    private Optional<Property> effectiveProperty(Type type, String name) {
        return type.getEffectiveProperties().stream().filter(property -> property.getName().equals(name)).findFirst();
    }

    /**
     * The supertype a class extends. TypeScript classes have one base class; the other supertypes must be
     * interface-only and are implemented. A mutable class extends the first supertype that is not interface-only,
     * else the first interface-only one; an immutable class extends its immutable supertype; a getter-only trait
     * extends its first trait. Mutable and immutable classes implement getter-only traits.
     */
    private ValueType primarySupertype(Type type) {
        List<ValueType> applications = type.getSuperTypeApplications();
        if (type.isGetterOnlyTrait()) {
            return applications.isEmpty() ? null : applications.get(0);
        }
        ValueType firstInterfaceOnly = null;
        for (ValueType application : applications) {
            Type parent = model.findType(application.getModelType()).orElseThrow();
            if (parent.isGetterOnlyTrait()) {
                continue;
            }
            if (type.isImmutable()) {
                if (parent.isImmutable()) {
                    return application;
                }
            } else if (!parent.isInterfaceOnly()) {
                return application;
            } else if (firstInterfaceOnly == null) {
                firstInterfaceOnly = application;
            }
        }
        return type.isImmutable() ? null : firstInterfaceOnly;
    }

    private Set<String> supertypeClosure(String typeName) {
        Set<String> closure = new java.util.LinkedHashSet<>();
        java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>(List.of(typeName));
        while (!pending.isEmpty()) {
            for (String parent : model.findType(pending.removeFirst()).orElseThrow().getSuperTypes()) {
                if (closure.add(parent)) {
                    pending.add(parent);
                }
            }
        }
        return closure;
    }

    private Map<String, Object> prepareType(Type type) {
        String name = typeNames.get(type.getName());
        Set<String> parameterNames = new HashSet<>();
        List<String> parameters = new ArrayList<>();
        List<String> declarations = new ArrayList<>();
        for (var parameter : type.getTypeParameters()) {
            String parameterName = typeIdentifier(parameter.getName(), type.getQualifiedName());
            if (typeNames.containsValue(parameterName) || !parameterNames.add(parameterName)) {
                throw invalidName(type.getQualifiedName(), parameterName, "conflicts with another type name");
            }
            parameters.add(parameterName);
            declarations.add(parameterDeclaration(parameterName, parameter));
        }

        Set<String> members = new HashSet<>(RESERVED_MEMBERS);
        for (Property property : type.getEffectiveProperties()) {
            String member = propertyName(type, property);
            if (!members.add(member) || member.startsWith(INTERNAL_PREFIX)) {
                throw invalidName(type.getQualifiedName() + "." + property.getName(), member,
                        "conflicts with another TypeScript member");
            }
        }

        ValueType primary = primarySupertype(type);
        Type base = primaryType(type);
        Set<String> inherited = new HashSet<>();
        if (base != null) {
            if (type.isGetterOnlyTrait()) {
                base.getEffectiveProperties().forEach(property -> inherited.add(property.getName()));
            } else {
                inherited.addAll(storage.get(base.getName()));
            }
        }
        Set<String> baseAbstract = base == null ? Set.of() : abstractSlots.get(base.getName());
        List<String> implemented = new ArrayList<>();
        for (ValueType application : type.getSuperTypeApplications()) {
            if (application != primary) {
                implemented.add(typeName(application));
            }
        }
        Set<String> secondary = secondarySupertypes(type);
        if (!implemented.isEmpty()) {
            supertypeDeclarations.add(Map.of("type", name, "supertypes", secondary.stream()
                    .map(typeNames::get).collect(Collectors.joining(", "))));
        }
        // A concrete type's brand keeps structurally equal classes apart. Classes that other classes implement
        // besides their base carry a public symbol brand, which the implementing classes declare too.
        List<String> brands = new ArrayList<>();
        if (!type.isInterfaceOnly()) {
            brands.add(symbolBrands.contains(type.getName())
                    ? "declare readonly [" + BRAND_PREFIX + name + "]: true;"
                    : "declare protected readonly " + BRAND_PREFIX + name + ": void;");
        }
        for (String implementedType : secondary) {
            if (symbolBrands.contains(implementedType)) {
                brands.add("declare readonly [" + BRAND_PREFIX + typeNames.get(implementedType) + "]: true;");
            }
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        String owner = apply(name, parameters);
        // Properties of supertypes besides the base class are declared on the class itself.
        Set<String> abstracted = abstractSlots.get(type.getName());
        for (Property property : type.getEffectiveProperties()) {
            String member = propertyName(type, property);
            if (inherited.contains(property.getName())) {
                // A redeclaration can change the literal default of the inherited storage.
                if (!type.isGetterOnlyTrait() && !type.isImmutable()) {
                    redeclaredDefault(base, property, member).ifPresent(properties::add);
                }
                continue;
            }
            boolean override = baseAbstract.contains(property.getName());
            if (abstracted.contains(property.getName())) {
                Property earlier = override ? effectiveProperty(base, property.getName()).orElseThrow() : null;
                if (earlier == null || !sameValueType(earlier.getValueType(), property.getValueType())) {
                    properties.add(field("abstract " + (override ? "override " : "") + "get " + member + "(): "
                            + valueType(property, false) + ";"));
                }
                continue;
            }
            properties.add(prepareMember(owner, member, property, override));
        }

        boolean hasBase = primary != null;
        String extendsClause = (hasBase ? " extends " + typeName(primary) : "")
                + (implemented.isEmpty() ? "" : " implements " + String.join(", ", implemented));
        String modifier = type.isInterfaceOnly() ? "abstract " : "";
        String readOnlyType = apply(READ_ONLY_PREFIX + name, parameters);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("declaration", "export " + modifier + "class " + apply(name, declarations) + extendsClause);
        // Interface-only types stay structural so that classes can implement them besides their base.
        result.put("brands", List.copyOf(brands));
        result.put("interfaceOnly", type.isInterfaceOnly());
        result.put("hasInstance", type.isInterfaceOnly() || symbolBrands.contains(type.getName()));
        // A subclass's constructor inherits Symbol.hasInstance (from Function at the latest), so redefining it
        // needs the override modifier under noImplicitOverride.
        result.put("staticOverride", base != null ? "override " : "");
        result.put("hasBase", hasBase);
        result.put("readOnlyType", readOnlyType);
        if (type.isGetterOnlyTrait()) {
            result.put("kind", "trait");
            result.put("members", traitMembers(type, base, inherited, hasBase, readOnlyType));
        } else {
            // Every class checks the effective properties of its own type (see TypeScriptValidationRenderModel).
            TypeScriptValidationRenderModel.prepare(type, base, result);
            if (type.isImmutable()) {
                result.put("kind", "immutable");
                prepareImmutable(type, base, inherited, result);
            } else {
                result.put("kind", "model");
                result.put("properties", List.copyOf(properties));
            }
        }
        return result;
    }

    /** A getter-only trait is an abstract contract that mutable and immutable classes implement. */
    private List<String> traitMembers(Type type, Type base, Set<String> inherited, boolean hasBase,
                                      String readOnlyType) {
        List<String> members = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            if (!inherited.contains(property.getName())) {
                members.add("abstract get " + propertyName(type, property) + "(): " + valueType(property, false) + ";");
            } else if (!sameValueType(effectiveProperty(base, property.getName()).orElseThrow().getValueType(),
                    property.getValueType())) {
                // A trait can narrow the getter it inherits.
                members.add("abstract override get " + propertyName(type, property) + "(): "
                        + valueType(property, false) + ";");
            }
        }
        if (hasBase) {
            members.add("abstract override asReadOnly(): " + readOnlyType + ";");
        } else {
            members.add("abstract asReadOnly(): " + readOnlyType + ";");
            members.add("abstract contentEquals(other: unknown): boolean;");
            members.add("abstract contentHashCode(): number;");
            members.add("abstract get vmfType(): vmf.ModelType;");
        }
        return members;
    }

    /** An immutable class receives all its values in the constructor and freezes the finished object. */
    private void prepareImmutable(Type type, Type base, Set<String> inherited, Map<String, Object> result) {
        List<String> inputs = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        List<String> assignments = new ArrayList<>();
        List<String> getters = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            String member = propertyName(type, property);
            String stored = valueType(property, true);
            String input = property.isCollection() ? "Iterable<" + elementType(property) + ">" : stored;
            inputs.add("        readonly " + member + "?: " + input + ";");
            if (inherited.contains(property.getName())) {
                Property earlier = effectiveProperty(base, property.getName()).orElseThrow();
                String field = "__vmfValue_" + member;
                // A redeclaration can change the literal default the base class would store.
                if (!property.isCollection() && !initialValue(property).equals(initialValue(earlier))) {
                    assignments.add("if (values." + member + " === undefined) {\n"
                            + "            (this as { " + field + ": " + stored + " })." + field + " = "
                            + initialValue(property) + ";\n        }");
                }
                // A narrowed reference keeps the base class's field; the constructor only accepts the narrower type.
                if (!sameValueType(earlier.getValueType(), property.getValueType())) {
                    getters.add("override get " + member + "(): " + stored + " {\n        return this." + field
                            + " as " + stored + ";\n    }");
                }
                continue;
            }
            String field = "__vmfValue_" + member;
            fields.add("/** @internal */\n    readonly " + field + ": " + stored + ";");
            assignments.add(property.isCollection()
                    ? "this." + field + " = vmf.ReadOnlyList.of(values." + member + " ?? []);"
                    : "this." + field + " = values." + member + " !== undefined ? values." + member + " : "
                        + initialValue(property) + ";");
            getters.add("get " + member + "(): " + stored + " {\n        return this." + field + ";\n    }");
        }
        result.put("parameter", inputs.isEmpty() ? "values: Readonly<Record<string, never>> = {}"
                : "values: {\n" + String.join("\n", inputs) + "\n    } = {}");
        result.put("fields", List.copyOf(fields));
        result.put("assignments", List.copyOf(assignments));
        result.put("getters", List.copyOf(getters));
    }

    /** The read-only view interface of a mutable type or getter-only trait. */
    private Map<String, Object> prepareReadOnly(Type type) {
        String name = typeNames.get(type.getName());
        List<String> parameters = new ArrayList<>();
        List<String> declarations = new ArrayList<>();
        for (var parameter : type.getTypeParameters()) {
            String parameterName = typeIdentifier(parameter.getName(), type.getQualifiedName());
            parameters.add(parameterName);
            declarations.add(parameterDeclaration(parameterName, parameter));
        }
        List<String> parents = type.getSuperTypeApplications().stream()
                .map(application -> READ_ONLY_PREFIX + typeName(application)).collect(Collectors.toList());
        List<String> members = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            members.add("readonly " + propertyName(type, property) + ": " + readOnlyType(property) + ";");
        }
        members.add("asReadOnly(): " + apply(READ_ONLY_PREFIX + name, parameters) + ";");
        members.add("contentEquals(other: unknown): boolean;");
        members.add("contentHashCode(): number;");
        members.add("readonly vmfType: vmf.ModelType;");
        if (!type.isGetterOnlyTrait()) {
            members.add("readonly vmfChanges: vmf.ReadOnlyModelChanges;");
        }
        return Map.of("type", name,
                "declaration", "export interface " + apply(READ_ONLY_PREFIX + name, declarations)
                        + (parents.isEmpty() ? "" : " extends " + String.join(", ", parents)),
                "members", List.copyOf(members));
    }

    /** Registers the reflection data of a type: name, annotations and properties in VMF property order. */
    private String modelTypeDeclaration(Type type) {
        StringBuilder declaration = new StringBuilder("vmf.declareModelType(").append(typeNames.get(type.getName()))
                .append(", ").append(quoted(type.getQualifiedName())).append(", ").append(type.isImmutable())
                .append(", ").append(annotations(type.getAnnotations())).append(", [");
        for (Property property : type.getEffectiveProperties()) {
            declaration.append("\n    [").append(quoted(propertyName(type, property))).append(", ")
                    .append(annotations(property.getAnnotations())).append("],");
        }
        if (!type.getEffectiveProperties().isEmpty()) {
            declaration.append("\n");
        }
        return declaration.append("]);").toString();
    }

    private static String annotations(List<ResolvedModel.MetadataAnnotation> annotations) {
        return annotations.stream().map(annotation -> "[" + quoted(annotation.getKey()) + ", "
                + quoted(annotation.getValue()) + "]").collect(Collectors.joining(", ", "[", "]"));
    }

    /**
     * The type of a property as a getter returns it. Mutable classes return their live lists, which getter-only
     * traits declare as {@code vmf.ListView}; immutable classes return a {@code vmf.ReadOnlyList}.
     */
    private String valueType(Property property, boolean immutable) {
        if (property.isCollection()) {
            if (immutable) {
                return "vmf.ReadOnlyList<" + elementType(property) + ">";
            }
            return (property.isOrdered() ? "vmf.ListView<" : "vmf.CollectionView<") + elementType(property) + ">";
        }
        ValueType value = property.getValueType();
        return typeName(value) + (value.isPrimitive() ? "" : " | null");
    }

    private String elementType(Property property) {
        return typeName(property.getValueType())
                + (property.getKind() == Property.Kind.SCALAR && property.allowsNullElements() ? " | null" : "");
    }

    /** The type of a property in a read-only view: model values and lists are read-only too. */
    private String readOnlyType(Property property) {
        ValueType value = property.getValueType();
        if (property.getKind() == Property.Kind.SCALAR) {
            return property.isCollection() ? "vmf.ListView<" + elementType(property) + ">"
                    : typeName(value) + (value.isPrimitive() ? "" : " | null");
        }
        String element = readOnlyTypeName(value);
        if (!property.isCollection()) {
            return element + " | null";
        }
        return (property.isOrdered() ? "vmf.ListView<" : "vmf.CollectionView<") + element + ">";
    }

    private String readOnlyTypeName(ValueType value) {
        if (value.getKind() == ValueType.Kind.PARAMETER) {
            return "vmf.ReadOnlyOf<" + typeName(value) + ">";
        }
        boolean immutable = model.findType(value.getModelType()).filter(Type::isImmutable).isPresent();
        return immutable ? typeName(value) : READ_ONLY_PREFIX + typeName(value);
    }

    /**
     * The scalar field of an inherited property whose redeclaration changes the literal default; it starts with the
     * new default when the object is created.
     */
    private Optional<Map<String, Object>> redeclaredDefault(Type base, Property property, String member) {
        if (base == null || property.getKind() != Property.Kind.SCALAR || property.isCollection()) {
            return Optional.empty();
        }
        Property earlier = effectiveProperty(base, property.getName()).orElseThrow();
        String initial = initialValue(property);
        if (initial.equals(initialValue(earlier))) {
            return Optional.empty();
        }
        String type = typeName(property.getValueType()) + (property.getValueType().isPrimitive() ? "" : " | null");
        return Optional.of(field("/** @internal */\n    override __vmfValue_" + member + ": " + type + " = "
                + initial + ";"));
    }

    private Map<String, Object> prepareMember(String owner, String member, Property property, boolean override) {
        Map<String, Object> declaration = prepareMember(owner, member, property);
        if (!override) {
            return declaration;
        }
        if (Boolean.TRUE.equals(declaration.get("container"))) {
            Map<String, Object> container = new LinkedHashMap<>(declaration);
            container.put("modifier", "override ");
            return container;
        }
        String text = (String) declaration.get("declaration");
        text = text.replace("    get " + member + "(", "    override get " + member + "(")
                .replace("    set " + member + "(", "    override set " + member + "(");
        if (text.startsWith("readonly " + member + " ")) {
            text = "override " + text;
        }
        return field(text);
    }

    private Map<String, Object> prepareMember(String owner, String member, Property property) {
        String target = typeName(property.getValueType());
        String name = quoted(member);
        switch (property.getKind()) {
            case CONTAINMENT: {
                String containerProperty = property.getOppositeName().isEmpty() ? ""
                        : ", " + quoted(checkedIdentifier(property.getOppositeName(), owner + "." + member + " opposite"));
                if (property.isCollection()) {
                    return field("readonly " + member + " = new vmf.ContainmentList<" + owner + ", "
                            + target + ">(this, " + name + ", child => child.__vmfContainerLink" + containerProperty + ");");
                }
                return field("/** @internal */\n"
                        + "    readonly __vmfSlot_" + member + " = new vmf.ContainmentSlot<" + owner + ", " + target
                        + ">(this, " + name + ", child => child.__vmfContainerLink" + containerProperty + ");\n\n"
                        + accessor(member, target, "this.__vmfSlot_" + member + ".value",
                                "this.__vmfSlot_" + member + ".set(value);"));
            }
            case CONTAINER:
                return prepareContainer(owner, member, property, target);
            case REFERENCE: {
                String opposite = property.getOppositeName();
                if (property.isCollection()) {
                    if (!property.isOrdered()) {
                        return field("readonly " + member + " = new vmf.ReferenceSet<" + target + ">(this, " + name + ");");
                    }
                    String arguments = "this, " + name + (opposite.isEmpty() ? "" : ", "
                            + quoted(checkedIdentifier(opposite, owner + "." + member + " opposite")));
                    return field("readonly " + member + " = new vmf.ReferenceList<" + target + ">(" + arguments + ");");
                }
                if (opposite.isEmpty()) {
                    return valueAccessor(member, target + " | null", "null");
                }
                String oppositeName = quoted(checkedIdentifier(opposite, owner + "." + member + " opposite"));
                return field("/** @internal */\n"
                        + "    readonly __vmfRef_" + member + " = new vmf.ReferenceSlot<" + target + ">(this, " + name + ");\n\n"
                        + accessor(member, target, "this.__vmfRef_" + member + ".value",
                                "vmf.setReference(this, value, " + name + ", " + oppositeName + ");"));
            }
            case SCALAR:
                if (property.isCollection()) {
                    String element = target + (property.allowsNullElements() ? " | null" : "");
                    return field("readonly " + member + " = new vmf.ScalarList<" + element + ">(this, " + name + ");");
                }
                return valueAccessor(member, target + (property.getValueType().isPrimitive() ? "" : " | null"),
                        initialValue(property));
            default:
                throw new IllegalStateException("Unsupported property kind " + property.getKind());
        }
    }

    /** A single value stored in a field and changed through {@code vmf.setValue}, which reports the change. */
    private static Map<String, Object> valueAccessor(String member, String type, String initial) {
        return field("/** @internal */\n"
                + "    __vmfValue_" + member + ": " + type + " = " + initial + ";\n\n"
                + "    get " + member + "(): " + type + " {\n"
                + "        return this.__vmfValue_" + member + ";\n"
                + "    }\n\n"
                + "    set " + member + "(value: " + type + ") {\n"
                + "        vmf.setValue(this, " + quoted(member) + ", value);\n"
                + "    }");
    }

    private static String accessor(String member, String type, String read, String write) {
        return "    get " + member + "(): " + type + " | null {\n"
                + "        return " + read + ";\n"
                + "    }\n\n"
                + "    set " + member + "(value: " + type + " | null) {\n"
                + "        " + write + "\n"
                + "    }";
    }

    /**
     * A container property reads the owner from the containment link. With a declared opposite it can also be set;
     * a shared container (no opposite) accepts every containment property that names it as its opposite.
     */
    private Map<String, Object> prepareContainer(String owner, String member, Property property, String target) {
        Type containerType = model.findType(property.getValueType().getModelType()).orElseThrow();
        List<String> owners = new ArrayList<>();
        String attach = "";
        if (property.getOppositeName().isEmpty()) {
            for (Type candidate : model.getTypes()) {
                if (!isSubtype(candidate, containerType.getName())) continue;
                for (Property containment : candidate.getEffectiveProperties()) {
                    if (containment.getKind() == Property.Kind.CONTAINMENT
                            && containment.getOppositeName().equals(property.getName())
                            && !owners.contains(containment.getName())) {
                        owners.add(containment.getName());
                    }
                }
            }
        } else {
            String opposite = checkedIdentifier(property.getOppositeName(), owner + "." + member + " opposite");
            owners.add(opposite);
            // Further containment properties of the container can name this end as their opposite.
            for (Property alternate : containerType.getEffectiveProperties()) {
                if (alternate.getKind() == Property.Kind.CONTAINMENT && !alternate.getName().equals(opposite)
                        && alternate.getOppositeName().equals(property.getName())) {
                    owners.add(alternate.getName());
                }
            }
            Property inverse = containerType.getEffectiveProperties().stream()
                    .filter(candidate -> candidate.getName().equals(opposite)).findFirst().orElseThrow();
            attach = inverse.isCollection() ? "value." + opposite + ".add(this)" : "value." + opposite + " = this";
        }
        return Map.of("container", true, "name", member, "type", target, "modifier", "",
                "ownerType", typeName(containerType),
                "owners", owners.stream().map(TypeScriptRenderModel::quoted).collect(Collectors.joining(", ")),
                "settable", !attach.isEmpty(), "attach", attach);
    }

    private boolean isSubtype(Type type, String superTypeName) {
        if (type.getName().equals(superTypeName)) return true;
        for (String parent : type.getSuperTypes()) {
            Optional<Type> parentType = model.findType(parent);
            if (parentType.isPresent() && isSubtype(parentType.get(), superTypeName)) return true;
        }
        return false;
    }

    private static String initialValue(Property property) {
        if (property.hasDeclaredDefault() && property.getScalarDefault().isPresent()) {
            ResolvedModel.ScalarDefault declared = property.getScalarDefault().get();
            return declared.isNull() ? "null" : literal(declared.getKind(), declared.getValue());
        }
        ValueType value = property.getValueType();
        if (!value.isPrimitive()) {
            return "null";
        }
        switch (value.getKind()) {
            case BOOLEAN:
                return "false";
            case INT64:
                return "0n";
            case CHAR:
                return quoted("\u0000");
            default:
                return "0";
        }
    }

    private static String literal(ValueType.Kind kind, Object value) {
        switch (kind) {
            case STRING:
                return quoted(String.valueOf(value));
            case BOOLEAN:
                return String.valueOf(value);
            case INT8:
            case INT16:
            case INT32:
                return String.valueOf(((Number) value).intValue());
            case INT64:
                return ((Number) value).longValue() + "n";
            case CHAR:
                return quoted(String.valueOf(value));
            case FLOAT32: {
                float number = ((Number) value).floatValue();
                if (Float.isNaN(number)) return "Number.NaN";
                if (Float.isInfinite(number)) return number > 0 ? "Number.POSITIVE_INFINITY" : "Number.NEGATIVE_INFINITY";
                return Float.toString(number);
            }
            case FLOAT64: {
                double number = ((Number) value).doubleValue();
                if (Double.isNaN(number)) return "Number.NaN";
                if (Double.isInfinite(number)) return number > 0 ? "Number.POSITIVE_INFINITY" : "Number.NEGATIVE_INFINITY";
                return Double.toString(number);
            }
            default:
                throw new IllegalStateException("Unsupported TypeScript default of kind " + kind);
        }
    }

    static String quoted(String text) {
        return TypeScriptSnapshotRenderModel.literal(text);
    }

    private static Map<String, Object> field(String declaration) {
        return Map.of("container", false, "declaration", declaration);
    }

    String typeName(Type type) {
        return typeNames.get(type.getName());
    }

    /**
     * A type parameter: unbounded and {@code Number}-bounded parameters hold scalars, which are numbers or bigints
     * for {@code Number}; other parameters extend their model or parameter bound.
     */
    private String parameterDeclaration(String name, ResolvedModel.TypeParameter parameter) {
        if (parameter.getBound() == null) {
            return name;
        }
        return name + " extends " + (parameter.isNumberBound() ? "number | bigint" : typeName(parameter.getBound()));
    }

    String typeName(ValueType value) {
        switch (value.getKind()) {
            case BOOLEAN:
                return "boolean";
            case INT8:
            case INT16:
            case INT32:
            case FLOAT32:
            case FLOAT64:
                return "number";
            case INT64:
                return "bigint";
            case CHAR:
            case STRING:
                return "string";
            case PARAMETER:
                return typeIdentifier(value.getParameterName(), "generic parameter");
            case MODEL:
                return apply(typeNames.get(value.getModelType()), value.getArguments().stream().map(this::typeName)
                        .collect(Collectors.toList()));
            default:
                throw new IllegalStateException("Unsupported TypeScript type " + value.getKind());
        }
    }

    String runtimeTypeName(ValueType value) {
        return typeNames.get(value.getModelType());
    }

    private static String apply(String name, List<String> arguments) {
        return arguments.isEmpty() ? name : name + "<" + String.join(", ", arguments) + ">";
    }

    static String propertyName(Type owner, Property property) {
        return checkedIdentifier(property.getName(), owner.getQualifiedName() + "." + property.getName());
    }

    private static String namespacePart(String name, String namespace) {
        String declaration = "namespace " + namespace;
        String part = checkedIdentifier(name, declaration);
        if (RESERVED_PATH_COMPONENT.matcher(part).matches()) {
            throw invalidName(declaration, part, "is not a portable output directory name");
        }
        return part;
    }

    private static String typeIdentifier(String name, String declaration) {
        String identifier = checkedIdentifier(name, declaration);
        return Character.toUpperCase(identifier.charAt(0)) + identifier.substring(1);
    }

    private static String checkedIdentifier(String name, String declaration) {
        if (!PORTABLE_IDENTIFIER.matcher(name).matches()) {
            throw invalidName(declaration, name, "must be a portable TypeScript identifier");
        }
        return name;
    }

    private static IllegalArgumentException invalidName(String declaration, String name, String reason) {
        return new IllegalArgumentException("Cannot generate TypeScript for " + declaration
                + ": name '" + name + "' " + reason);
    }
}
