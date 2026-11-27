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
package eu.mihosoft.vmf.core.codegen;

import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks what the TypeScript backend binds to host code: external value types, bound default values and behavior
 * delegation. The generated module asks for the host code it needs through its {@code ModelBindings} interface.
 */
public final class TypeScriptCapabilities {
    /** On an {@code @ExternalType} stub: the TypeScript type, a type name or a union of string literals. */
    public static final String EXTERNAL_TYPE_ANNOTATION = "vmf:typescript:external-type";
    /** On an {@code @ExternalType} stub: the module that exports the TypeScript type. */
    public static final String EXTERNAL_MODULE_ANNOTATION = "vmf:typescript:external-module";
    /** On a property: its non-literal {@code @DefaultValue} comes from the installed bindings. */
    public static final String BIND_DEFAULT_ANNOTATION = "vmf:typescript:bind-default";
    public static final String UUID = "java.util.UUID";

    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "clone", "finalize");
    private static final Pattern LITERAL = Pattern.compile("\\s*\"([^\"\\\\\\x00-\\x1f]+)\"\\s*");
    private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private TypeScriptCapabilities() {
    }

    /** UUID, or an external type whose stub names its TypeScript type. */
    public static boolean supportsExternalType(ResolvedModel model, ValueType type) {
        return type.getKind() == ValueType.Kind.EXTERNAL && (UUID.equals(type.getExternalType())
                || model.externalAnnotations(type.getExternalType()).stream()
                        .anyMatch(annotation -> annotation.getKey().equals(EXTERNAL_TYPE_ANNOTATION)));
    }

    /** {@code java.lang.Object} without a host mapping: an untyped JSON-like value. */
    public static boolean isUnboundObject(ResolvedModel model, ValueType type) {
        return isUnboundObject(model, type.getExternalType());
    }

    /** {@code java.lang.Object} without a host mapping, by its source name. */
    public static boolean isUnboundObject(ResolvedModel model, String externalType) {
        return Object.class.getName().equals(externalType) && model.externalAnnotations(externalType).isEmpty();
    }

    /** A single value or an ordered list of an external type, the shapes C# also accepts. */
    static boolean supportsExternalProperty(ResolvedModel model, Property property) {
        return property.getKind() == Property.Kind.SCALAR && supportsExternalType(model, property.getValueType())
                && (!property.isCollection() || property.isOrdered() && !property.isUnique()
                    && (property.getUpperBound() < 0 || property.allowsNullElements()));
    }

    /**
     * An untyped value on a mutable owner (optional single value or ordered list with null elements), or a
     * getter-only single value that subtypes may narrow.
     */
    static boolean supportsObjectProperty(ResolvedModel model, Type owner, Property property) {
        if (property.getKind() != Property.Kind.SCALAR || !isUnboundObject(model, property.getValueType())
                || property.hasDeclaredDefault() || !property.getConstraints().isEmpty() || owner.isImmutable()) {
            return false;
        }
        if (property.isGetterOnly()) {
            return !property.isCollection();
        }
        return property.isCollection()
                ? property.isOrdered() && !property.isUnique() && property.allowsNullElements()
                : property.getLowerBound() == 0 && property.getUpperBound() == 1;
    }

    /** The literals of an external type mapped to a union of string literals, in declaration order. */
    public static Optional<List<String>> literals(ResolvedModel model, ValueType type) {
        if (type.getKind() != ValueType.Kind.EXTERNAL) return Optional.empty();
        return literals(model, type.getExternalType());
    }

    /** The literals of an external type, by its source name. */
    public static Optional<List<String>> literals(ResolvedModel model, String externalType) {
        return mapping(model, externalType).flatMap(TypeScriptCapabilities::parseLiterals);
    }

    /** The value of the external type annotation of an external type, if there is exactly one. */
    public static Optional<String> mapping(ResolvedModel model, String externalType) {
        var values = model.externalAnnotations(externalType).stream()
                .filter(annotation -> annotation.getKey().equals(EXTERNAL_TYPE_ANNOTATION))
                .map(ResolvedModel.MetadataAnnotation::getValue).toArray(String[]::new);
        return values.length == 1 ? Optional.of(values[0].trim()) : Optional.empty();
    }

    /** Parses {@code "A" | "B"}; empty if the text is not a union of distinct string literals. */
    static Optional<List<String>> parseLiterals(String text) {
        if (!text.trim().startsWith("\"")) return Optional.empty();
        var literals = new LinkedHashSet<String>();
        for (String part : text.split("\\|", -1)) {
            Matcher matcher = LITERAL.matcher(part);
            if (!matcher.matches() || !literals.add(matcher.group(1))) return Optional.empty();
        }
        return Optional.of(List.copyOf(literals));
    }

    /** Reports an external type mapping that names no TypeScript type the generator can use. */
    static void validateMapping(ResolvedModel model, ValueType type, String name, List<String> diagnostics) {
        if (type.getKind() != ValueType.Kind.EXTERNAL || UUID.equals(type.getExternalType())
                || !supportsExternalType(model, type)) return;
        String external = type.getExternalType();
        var mapping = mapping(model, external);
        long modules = model.externalAnnotations(external).stream()
                .filter(annotation -> annotation.getKey().equals(EXTERNAL_MODULE_ANNOTATION)).count();
        boolean literalUnion = mapping.filter(text -> text.startsWith("\"")).isPresent();
        if (mapping.isEmpty()) {
            diagnostics.add(name + " needs one " + EXTERNAL_TYPE_ANNOTATION + " on the stub of " + external);
        } else if (literalUnion ? parseLiterals(mapping.get()).isEmpty() || modules > 0
                : !isTypeName(mapping.get()) || modules > 1 || modules == 1 && mapping.get().contains(".")) {
            diagnostics.add(name + " maps " + external + " to '" + mapping.get() + "', which needs to be a TypeScript"
                    + " type name (an identifier when imported from one " + EXTERNAL_MODULE_ANNOTATION
                    + ") or a union of distinct string literals");
        }
    }

    /** Whether the TypeScript type is a name: a global type, a primitive or an imported type. */
    public static boolean isTypeName(String text) {
        return QUALIFIED_NAME.matcher(text).matches();
    }

    /**
     * The literal that a default such as {@code Level.HIGH} names, when the property's external type is a union of
     * string literals that contains the expression's last name segment.
     */
    public static Optional<String> literalDefault(ResolvedModel model, Property property) {
        if (property.getKind() != Property.Kind.SCALAR || property.isCollection()) return Optional.empty();
        var literals = literals(model, property.getValueType());
        var expression = property.getDefaultExpression().map(String::trim);
        if (literals.isEmpty() || expression.isEmpty() || !QUALIFIED_NAME.matcher(expression.get()).matches()) {
            return Optional.empty();
        }
        String text = expression.get();
        String name = text.substring(text.lastIndexOf('.') + 1);
        return literals.get().contains(name) ? Optional.of(name) : Optional.empty();
    }

    /** A non-literal scalar default whose value the installed bindings supply. */
    public static boolean bindsDefault(Property property) {
        return property.hasDeclaredDefault() && property.getScalarDefault().isEmpty()
                && property.getKind() == Property.Kind.SCALAR && !property.isCollection()
                && property.getAnnotationValues(BIND_DEFAULT_ANNOTATION).equals(List.of("true"));
    }

    /** The declared defaults TypeScript generates: literals, bound expressions and literal union members. */
    static boolean acceptsDefault(ResolvedModel model, Property property) {
        if (property.getKind() != Property.Kind.SCALAR || property.isCollection()) return false;
        return property.getScalarDefault().isPresent() || bindsDefault(property)
                || literalDefault(model, property).isPresent();
    }

    /** Reports misplaced binding annotations. */
    static void validateDefault(Property property, String name, List<String> diagnostics) {
        if (!property.getAnnotationValues(BIND_DEFAULT_ANNOTATION).isEmpty() && !bindsDefault(property)) {
            diagnostics.add(name + " needs a non-literal scalar @DefaultValue for " + BIND_DEFAULT_ANNOTATION + "=true");
        }
    }

    /**
     * Delegated methods become class methods that call the object's behavior. TypeScript has one method per name:
     * overloads must differ in their number of parameters.
     */
    static void validateBehavior(ResolvedModel model, Type type, List<String> diagnostics) {
        String name = type.getQualifiedName();
        if (!type.getTypeParameters().isEmpty()) {
            diagnostics.add(name + " needs a nongeneric type for TypeScript behavior delegation");
        }
        Map<String, List<CallableMethod>> overloads = new LinkedHashMap<>();
        for (var method : type.getEffectiveMethods()) {
            String callable = name + "." + method.getName();
            overloads.computeIfAbsent(method.getName(), unused -> new ArrayList<>()).add(method);
            if (method.getJavaBinding().isBlank() && !type.isInterfaceOnly()) {
                diagnostics.add(callable + " needs a source method binding for TypeScript behavior delegation");
            }
            if (method.isStatic() || method.hasCheckedExceptions()
                    || (OBJECT_METHODS.contains(method.getName()) && !isToString(method))) {
                diagnostics.add(callable + " needs an instance behavior method without checked exceptions"
                        + " or Object overrides other than toString()");
            }
            var parameters = method.getParameters();
            if (!isCallableResult(model, method.getReturnType())
                    || parameters.stream().anyMatch(parameter -> !isCallableParameter(model, parameter.getType()))) {
                diagnostics.add(callable + " needs portable scalars, concrete models, closed model applications, "
                        + "mapped external types, or arrays of these. Only arguments can use jagged arrays. Singular "
                        + "arguments and results also support named mutable abstract models and Object. Results can "
                        + "also use List or VList of scalars, models, or Object");
            }
            if (!parameters.isEmpty() && parameters.get(parameters.size() - 1).isVarArg()
                    && parameters.subList(0, parameters.size() - 1).stream()
                            .anyMatch(parameter -> parameter.getType().getKind() == CallableType.Kind.ARRAY)) {
                diagnostics.add(callable + " needs a single array parameter when using variable arguments");
            }
        }
        for (var group : overloads.entrySet()) {
            if (group.getValue().size() < 2) continue;
            var arities = new java.util.HashSet<Integer>();
            boolean distinct = group.getValue().stream().allMatch(method -> arities.add(method.getParameters().size())
                    && method.getParameters().stream().noneMatch(ResolvedModel.CallableParameter::isVarArg));
            if (!distinct) {
                diagnostics.add(name + "." + group.getKey() + " needs overloads that differ in their number of"
                        + " parameters and take no variable arguments: TypeScript has one method per name");
            }
        }
    }

    static boolean isToString(CallableMethod method) {
        return method.getName().equals("toString") && method.getParameters().isEmpty()
                && method.getReturnType().getKind() == CallableType.Kind.VALUE
                && method.getReturnType().getValueType().getKind() == ValueType.Kind.STRING;
    }

    private static boolean isCallableResult(ResolvedModel model, CallableType type) {
        if (type.getKind() == CallableType.Kind.LIST) {
            var element = type.getElementType();
            return element.getKind() == CallableType.Kind.VALUE && element.getValueType().getArguments().isEmpty()
                    && isCallableResult(model, element);
        }
        if (type.getKind() == CallableType.Kind.VALUE) {
            var value = type.getValueType();
            if (isUnboundObject(model, value)) return true;
            if (value.getKind() == ValueType.Kind.MODEL && value.getArguments().isEmpty()
                    && model.findType(value.getModelType()).filter(target -> target.isInterfaceOnly()
                            && !target.isImmutable() && !target.isGetterOnlyTrait()
                            && target.getTypeParameters().isEmpty()).isPresent()) return true;
        }
        return isCallableType(model, type);
    }

    private static boolean isCallableParameter(ResolvedModel model, CallableType type) {
        while (type.getKind() == CallableType.Kind.ARRAY && type.getElementType().getKind() == CallableType.Kind.ARRAY) {
            type = type.getElementType();
        }
        return type.getKind() == CallableType.Kind.VALUE ? isCallableResult(model, type) : isCallableType(model, type);
    }

    private static boolean isCallableType(ResolvedModel model, CallableType type) {
        if (type.getKind() == CallableType.Kind.VOID) return true;
        var component = type.getKind() == CallableType.Kind.ARRAY ? type.getElementType() : type;
        if (component.getKind() != CallableType.Kind.VALUE) return false;
        var value = component.getValueType();
        if (value.isPortableScalar()) return true;
        if (value.getKind() == ValueType.Kind.EXTERNAL) return supportsExternalType(model, value);
        if (value.getKind() != ValueType.Kind.MODEL) return false;
        var target = model.findType(value.getModelType()).orElse(null);
        var arguments = value.getArguments();
        if (target == null || target.getTypeParameters().size() != arguments.size()) return false;
        if (arguments.isEmpty()) return !target.isInterfaceOnly();
        // TypeScript type parameters have model bounds, so closed applications use named nongeneric models.
        return arguments.stream().allMatch(argument -> argument.getKind() == ValueType.Kind.MODEL
                && argument.getArguments().isEmpty() && model.findType(argument.getModelType())
                        .filter(declaration -> declaration.getTypeParameters().isEmpty()
                                && !declaration.isGetterOnlyTrait()).isPresent());
    }
}
