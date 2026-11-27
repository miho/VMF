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
            "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable", "toLocaleString", "toString", "valueOf");
    private static final String BRAND_PREFIX = "__vmfBrand_";

    private final String outputPath;
    private final Map<String, String> typeNames = new LinkedHashMap<>();
    private final List<Map<String, Object>> types;
    private final TypeScriptSnapshotRenderModel snapshots;

    TypeScriptRenderModel(ResolvedModel model) {
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
            if (!members.add(member) || member.startsWith(BRAND_PREFIX)) {
                throw invalidName(type.getQualifiedName() + "." + property.getName(), member,
                        "conflicts with another TypeScript member");
            }
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        List<String> containments = new ArrayList<>();
        List<Map<String, Object>> multiplicities = new ArrayList<>();
        String owner = apply(name, parameters);
        for (Property property : type.getProperties()) {
            String member = propertyName(type, property);
            properties.add(prepareMember(owner, member, property));
            if (property.getKind() == Property.Kind.CONTAINMENT) {
                containments.add(member);
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
        switch (property.getKind()) {
            case CONTAINMENT:
                return field("readonly " + member + " = new vmf.ContainmentList<" + owner + ", "
                        + target + ">(this, child => child.__vmfContainerLink);");
            case CONTAINER:
                return Map.of("container", true, "name", member, "type", target,
                        "opposite", checkedIdentifier(property.getOppositeName(), owner + "." + member + " opposite"));
            case REFERENCE:
                if (property.isCollection()) {
                    return field("readonly " + member + " = new vmf.ReferenceSet<" + target + ">();");
                }
                return field(member + ": " + target + " | null = null;");
            case SCALAR:
                if (property.getValueType().isPrimitive()) {
                    String initial = property.getValueType().getKind() == ValueType.Kind.BOOLEAN ? "false" : "0";
                    return field(member + ": " + target + " = " + initial + ";");
                }
                return field(member + ": " + target + " | null = null;");
            default:
                throw new IllegalStateException("Unsupported property kind " + property.getKind());
        }
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
                return "number";
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
