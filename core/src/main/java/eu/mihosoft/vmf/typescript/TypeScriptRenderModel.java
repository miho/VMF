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
            "contentEquals", "contentHashCode", "deepCopy",
            "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "toLocaleString", "toString", "valueOf");
    private static final String BRAND_PREFIX = "__vmfBrand_";
    private static final String INTERNAL_PREFIX = "__vmf";

    private final ResolvedModel model;
    private final String outputPath;
    private final Map<String, String> typeNames = new LinkedHashMap<>();
    private final List<Map<String, Object>> types;
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

        List<Type> ordered = parentFirst(model);
        types = ordered.stream().map(this::prepareType).collect(Collectors.toUnmodifiableList());
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

        List<Map<String, Object>> properties = new ArrayList<>();
        List<Map<String, Object>> containments = new ArrayList<>();
        List<Map<String, Object>> multiplicities = new ArrayList<>();
        String owner = apply(name, parameters);
        for (Property property : type.getProperties()) {
            String member = propertyName(type, property);
            properties.add(prepareMember(owner, member, property));
            if (property.getKind() == Property.Kind.CONTAINMENT) {
                containments.add(Map.of("name", member, "many", property.isCollection()));
            }
            addMultiplicity(property, member, multiplicities);
        }

        boolean hasBase = !type.getSuperTypeApplications().isEmpty();
        String base = hasBase ? " extends " + typeName(type.getSuperTypeApplications().get(0)) : "";
        String modifier = type.isInterfaceOnly() ? "abstract " : "";
        boolean hasValidation = !containments.isEmpty() || !multiplicities.isEmpty();
        return Map.of(
                "declaration", "export " + modifier + "class " + apply(name, declarations) + base,
                "brand", BRAND_PREFIX + name,
                "hasBase", hasBase,
                "hasValidation", hasValidation,
                "emitValidation", !hasBase || hasValidation,
                "properties", List.copyOf(properties),
                "containments", List.copyOf(containments),
                "multiplicities", List.copyOf(multiplicities));
    }

    private Map<String, Object> prepareMember(String owner, String member, Property property) {
        String target = typeName(property.getValueType());
        String name = quoted(member);
        switch (property.getKind()) {
            case CONTAINMENT:
                if (property.isCollection()) {
                    return field("readonly " + member + " = new vmf.ContainmentList<" + owner + ", "
                            + target + ">(this, " + name + ", child => child.__vmfContainerLink);");
                }
                return field("/** @internal */\n"
                        + "    readonly __vmfSlot_" + member + " = new vmf.ContainmentSlot<" + owner + ", " + target
                        + ">(this, " + name + ", child => child.__vmfContainerLink);\n\n"
                        + accessor(member, target, "this.__vmfSlot_" + member + ".value",
                                "this.__vmfSlot_" + member + ".set(value);"));
            case CONTAINER:
                return prepareContainer(owner, member, property, target);
            case REFERENCE: {
                String opposite = property.getOppositeName();
                if (property.isCollection()) {
                    if (!property.isOrdered()) {
                        return field("readonly " + member + " = new vmf.ReferenceSet<" + target + ">();");
                    }
                    String arguments = opposite.isEmpty() ? "this" : "this, " + name + ", "
                            + quoted(checkedIdentifier(opposite, owner + "." + member + " opposite"));
                    return field("readonly " + member + " = new vmf.ReferenceList<" + target + ">(" + arguments + ");");
                }
                if (opposite.isEmpty()) {
                    return field(member + ": " + target + " | null = null;");
                }
                String oppositeName = quoted(checkedIdentifier(opposite, owner + "." + member + " opposite"));
                return field("/** @internal */\n"
                        + "    readonly __vmfRef_" + member + " = new vmf.ReferenceSlot<" + target + ">();\n\n"
                        + accessor(member, target, "this.__vmfRef_" + member + ".value",
                                "vmf.setReference(this, value, " + name + ", " + oppositeName + ");"));
            }
            case SCALAR:
                if (property.isCollection()) {
                    String element = target + (property.allowsNullElements() ? " | null" : "");
                    return field("readonly " + member + " = new vmf.ScalarList<" + element + ">();");
                }
                if (property.getValueType().isPrimitive()) {
                    return field(member + ": " + target + " = " + initialValue(property) + ";");
                }
                return field(member + ": " + target + " | null = " + initialValue(property) + ";");
            default:
                throw new IllegalStateException("Unsupported property kind " + property.getKind());
        }
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
            case INT32:
                return String.valueOf(((Number) value).intValue());
            case INT64:
                return ((Number) value).longValue() + "n";
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

    private static void addMultiplicity(Property property, String name, List<Map<String, Object>> checks) {
        boolean collection = property.isCollection();
        if (!collection && property.getValueType().isPrimitive()) {
            return;
        }
        int lower = property.getLowerBound();
        int upper = property.getUpperBound();
        boolean checkLower = lower > 0;
        boolean checkUpper = collection && upper >= 0;
        if (!checkLower && !checkUpper) {
            return;
        }

        String count = collection ? "this." + name + ".count" : "this." + name + " === null ? 0 : 1";
        String variable = "count" + checks.size();
        List<String> conditions = new ArrayList<>();
        if (checkLower) {
            conditions.add(variable + " < " + lower);
        }
        if (checkUpper) {
            conditions.add(variable + " > " + upper);
        }
        String expected = checkLower && checkUpper ? "between " + lower + " and " + upper
                : checkLower ? "at least " + lower : "at most " + upper;
        checks.add(Map.of("name", name, "variable", variable, "count", count,
                "condition", String.join(" || ", conditions), "expected", expected));
    }

    String typeName(Type type) {
        return typeNames.get(type.getName());
    }

    String typeName(ValueType value) {
        switch (value.getKind()) {
            case BOOLEAN:
                return "boolean";
            case INT32:
            case FLOAT64:
                return "number";
            case INT64:
                return "bigint";
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
