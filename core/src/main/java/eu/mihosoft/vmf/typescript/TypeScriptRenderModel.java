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
            declarations.add(parameterName + " extends " + typeName(parameter.getBound()));
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
        Set<String> inherited = new HashSet<>();
        if (primary != null) {
            model.findType(primary.getModelType()).orElseThrow().getEffectiveProperties()
                    .forEach(property -> inherited.add(property.getName()));
        }
        List<String> implemented = new ArrayList<>();
        for (ValueType application : type.getSuperTypeApplications()) {
            if (application != primary) {
                implemented.add(typeName(application));
            }
        }
        if (!implemented.isEmpty()) {
            Set<String> secondary = supertypeClosure(type.getName());
            secondary.removeAll(primary == null ? Set.of() : supertypeClosure(primary.getModelType()));
            if (primary != null) {
                secondary.remove(primary.getModelType());
            }
            supertypeDeclarations.add(Map.of("type", name, "supertypes", secondary.stream()
                    .map(typeNames::get).collect(Collectors.joining(", "))));
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        String owner = apply(name, parameters);
        // Properties of interface-only supertypes besides the base class are declared on the class itself.
        for (Property property : type.getEffectiveProperties()) {
            if (inherited.contains(property.getName())) {
                continue;
            }
            String member = propertyName(type, property);
            properties.add(prepareMember(owner, member, property));
        }

        boolean hasBase = primary != null;
        String base = (hasBase ? " extends " + typeName(primary) : "")
                + (implemented.isEmpty() ? "" : " implements " + String.join(", ", implemented));
        String modifier = type.isInterfaceOnly() ? "abstract " : "";
        String readOnlyType = apply(READ_ONLY_PREFIX + name, parameters);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("declaration", "export " + modifier + "class " + apply(name, declarations) + base);
        // Interface-only types stay structural so that classes can implement them besides their base.
        result.put("brand", type.isInterfaceOnly() ? "" : BRAND_PREFIX + name);
        result.put("interfaceOnly", type.isInterfaceOnly());
        result.put("hasBase", hasBase);
        result.put("readOnlyType", readOnlyType);
        if (type.isGetterOnlyTrait()) {
            result.put("kind", "trait");
            result.put("members", traitMembers(type, inherited, hasBase, readOnlyType));
        } else {
            // Every class checks the effective properties of its own type (see TypeScriptValidationRenderModel).
            TypeScriptValidationRenderModel.prepare(type,
                    hasBase ? model.findType(primary.getModelType()).orElseThrow() : null, result);
            if (type.isImmutable()) {
                result.put("kind", "immutable");
                prepareImmutable(type, inherited, result);
            } else {
                result.put("kind", "model");
                result.put("properties", List.copyOf(properties));
            }
        }
        return result;
    }

    /** A getter-only trait is an abstract contract that mutable and immutable classes implement. */
    private List<String> traitMembers(Type type, Set<String> inherited, boolean hasBase, String readOnlyType) {
        List<String> members = new ArrayList<>();
        for (Property property : type.getEffectiveProperties()) {
            if (!inherited.contains(property.getName())) {
                members.add("abstract get " + propertyName(type, property) + "(): " + valueType(property, false) + ";");
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
    private void prepareImmutable(Type type, Set<String> inherited, Map<String, Object> result) {
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
            declarations.add(parameterName + " extends " + typeName(parameter.getBound()));
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
            Property inverse = containerType.getEffectiveProperties().stream()
                    .filter(candidate -> candidate.getName().equals(opposite)).findFirst().orElseThrow();
            attach = inverse.isCollection() ? "value." + opposite + ".add(this)" : "value." + opposite + " = this";
        }
        return Map.of("container", true, "name", member, "type", target,
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
