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
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableParameter;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Delegated methods ({@code @DelegateTo}) and construction hooks. Each model type that declares methods or a hook
 * gets a {@code <Type>Behavior} interface whose methods receive the object they are called on first. The installed
 * bindings create one default behavior per object and declaring type, when the object first needs it; an object's
 * {@code replaceBehavior} replaces all of them for that object.
 */
final class TypeScriptBehaviorRenderModel {
    private static final Set<String> RESERVED_WORDS = Set.of("break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
            "function", "if", "import", "in", "instanceof", "new", "null", "return", "super", "switch", "this",
            "throw", "true", "try", "typeof", "var", "void", "while", "with", "yield", "let", "static",
            "implements", "interface", "package", "private", "protected", "public", "await", "arguments", "eval",
            "caller", "args");
    static final String REPLACE = "replaceBehavior";
    /** The members of Object.prototype, which every class inherits. */
    private static final Set<String> OBJECT_MEMBERS = Set.of("constructor", "toString", "toLocaleString", "valueOf",
            "hasOwnProperty", "isPrototypeOf", "propertyIsEnumerable");

    private final ResolvedModel model;
    private final TypeScriptRenderModel source;
    /** Declaring types and the methods they declare, in model order. */
    private final Map<String, List<CallableMethod>> declared = new LinkedHashMap<>();
    /** Declaring types whose behavior the bindings create, with their hook name if they declare one. */
    private final Map<String, String> bound = new LinkedHashMap<>();

    TypeScriptBehaviorRenderModel(ResolvedModel model, TypeScriptRenderModel source, Set<String> emittedNames) {
        this.model = model;
        this.source = source;
        for (Type type : model.getTypes()) {
            for (CallableMethod method : type.getEffectiveMethods()) {
                var methods = declared.computeIfAbsent(method.getDeclaringType(), unused -> new ArrayList<>());
                if (methods.stream().noneMatch(known -> sameSlot(known, method))) {
                    methods.add(method);
                }
                if (!method.getJavaBinding().isBlank()) {
                    bound.putIfAbsent(method.getDeclaringType(), "");
                }
            }
            hook(type).ifPresent(hook -> {
                declared.computeIfAbsent(hook.getDeclaringType(), unused -> new ArrayList<>());
                bound.put(hook.getDeclaringType(), hook.getName());
            });
        }
        for (String declaring : declared.keySet()) {
            String name = interfaceName(declaring);
            if (!emittedNames.add(name)) {
                throw new IllegalArgumentException("Cannot generate TypeScript behavior for " + declaring
                        + ": '" + name + "' conflicts with another TypeScript declaration");
            }
        }
    }

    boolean isEmpty() {
        return declared.isEmpty();
    }

    /** The construction hook a mutable type runs; immutable objects have none, as in Java. */
    private Optional<ResolvedModel.ConstructionHook> hook(Type type) {
        return type.isImmutable() ? Optional.empty() : type.getConstructionHook();
    }

    boolean hasHook(Type type) {
        return hook(type).isPresent();
    }

    /** The {@code <Type>Behavior} interfaces. */
    List<String> getInterfaces() {
        var result = new ArrayList<String>();
        for (var entry : declared.entrySet()) {
            Type owner = declaringType(entry.getKey());
            String caller = source.typeName(owner);
            var lines = new ArrayList<String>();
            lines.add("/**");
            lines.add(" * The behavior of the methods that {@link " + caller + "} declares: each method receives the object");
            lines.add(" * it is called on (`caller`) and the call's arguments.");
            lines.add(" */");
            lines.add("export interface " + interfaceName(entry.getKey()) + " {");
            String hook = bound.getOrDefault(entry.getKey(), "");
            if (!hook.isEmpty()) {
                lines.add("    /** Runs once for every new " + caller + ", before its declared defaults are applied. */");
                lines.add("    " + checked(hook, owner) + "?(caller: " + caller + "): void;");
            }
            for (CallableMethod method : entry.getValue()) {
                var parameters = new ArrayList<String>();
                parameters.add("caller: " + caller);
                for (CallableParameter parameter : method.getParameters()) {
                    parameters.add(parameterName(parameter) + ": " + parameterType(parameter));
                }
                lines.add("    " + checked(method.getName(), owner) + "(" + String.join(", ", parameters) + "): "
                        + resultType(method.getReturnType()) + ";");
            }
            lines.add("}");
            result.add(String.join("\n", lines));
        }
        return result;
    }

    /** {@code ModelBindings.behaviors} members, by declaring type. */
    Map<String, String> getBindings() {
        var result = new LinkedHashMap<String, String>();
        for (String declaring : bound.keySet()) {
            result.put(declaring, "() => " + interfaceName(declaring));
        }
        return result;
    }

    /** The module functions that create default behaviors from the installed bindings. */
    List<String> getFactories() {
        return bound.keySet().stream().map(declaring -> "const " + factory(declaring) + " = (): "
                + interfaceName(declaring) + " => __vmfBindings.use(" + TypeScriptRenderModel.quoted("behaviors." + declaring)
                + ", bindings => bindings.behaviors." + declaring + ")();").collect(Collectors.toList());
    }

    /** The statement of a class constructor that runs the construction hook. */
    String hookCall(Type type) {
        return hook(type).map(hook -> "vmf.defaultBehaviorOf<" + interfaceName(hook.getDeclaringType()) + ">(this, "
                + TypeScriptRenderModel.quoted(hook.getDeclaringType()) + ", " + factory(hook.getDeclaringType()) + ")."
                + hook.getName() + "?.(this);").orElse("");
    }

    /** {@code vmf.declareBehavior} for the classes with delegated methods, used to check replacements. */
    List<String> getDeclarations(List<Type> ordered) {
        var result = new ArrayList<String>();
        for (Type type : ordered) {
            if (type.isImmutable() || type.isGetterOnlyTrait() || type.getEffectiveMethods().isEmpty()) continue;
            String names = type.getEffectiveMethods().stream().map(CallableMethod::getName).distinct()
                    .map(TypeScriptRenderModel::quoted).collect(Collectors.joining(", "));
            result.add("vmf.declareBehavior(" + source.typeName(type) + ", [" + names + "]);");
        }
        return result;
    }

    /**
     * The methods a class declares: the effective methods it does not inherit unchanged from its base class, with
     * all overloads of a name together.
     */
    List<String> methods(Type type, Type base, Set<String> members) {
        var names = new LinkedHashMap<String, List<CallableMethod>>();
        for (CallableMethod method : type.getEffectiveMethods()) {
            names.computeIfAbsent(method.getName(), unused -> new ArrayList<>()).add(method);
        }
        var result = new ArrayList<String>();
        for (var group : names.entrySet()) {
            boolean inherited = base != null && group.getValue().stream().allMatch(method -> base.getEffectiveMethods()
                    .stream().anyMatch(candidate -> sameSlot(candidate, method)
                            && candidate.getDeclaringType().equals(method.getDeclaringType())
                            && candidate.getJavaBinding().equals(method.getJavaBinding())));
            if (inherited) continue;
            String name = checked(group.getKey(), type);
            boolean toString = group.getValue().size() == 1 && isToString(group.getValue().get(0));
            if (!toString && (members.contains(name) || name.equals(REPLACE))) {
                throw new IllegalArgumentException("Cannot generate TypeScript for " + type.getQualifiedName() + "."
                        + name + ": the method name conflicts with another TypeScript member");
            }
            // A class with a base class overrides the members of Object.prototype (toString) too.
            boolean override = base != null && (OBJECT_MEMBERS.contains(group.getKey())
                    || base.getEffectiveMethods().stream().anyMatch(candidate -> candidate.getName().equals(group.getKey())));
            result.add(group.getValue().size() == 1 ? single(name, group.getValue().get(0), override)
                    : overloads(type, name, group.getValue(), override));
        }
        return result;
    }

    /** {@code replaceBehavior} of a mutable class whose delegated methods differ from its base class. */
    String replace(Type type, Type base) {
        if (!replaces(type)) return "";
        var contracts = contracts(type);
        boolean override = base != null && replaces(base);
        if (override && contracts(base).equals(contracts)) return "";
        return "/**\n"
                + "     * Replaces the behavior of this object: its delegated methods call the replacement from now on, which\n"
                + "     * must implement all of them. Copies and objects read from documents use the default behavior.\n"
                + "     */\n"
                + "    " + (override ? "override " : "") + REPLACE + "(behavior: " + String.join(" & ", contracts) + "): void {\n"
                + "        vmf.replaceBehavior(this, behavior);\n"
                + "    }";
    }

    private static boolean replaces(Type type) {
        return !type.isImmutable() && !type.isGetterOnlyTrait() && !type.getEffectiveMethods().isEmpty();
    }

    /**
     * The behavior interfaces a replacement must implement: those of the type's delegated methods. A class that
     * implements supertypes with delegated methods besides its base class also takes the interfaces that their
     * {@code replaceBehavior} and its base class's take, so that it is compatible with all of them.
     */
    private Set<String> contracts(Type type) {
        var result = new LinkedHashSet<String>();
        type.getEffectiveMethods().forEach(method -> result.add(interfaceName(method.getDeclaringType())));
        Type base = source.primaryType(type);
        var secondaries = type.getSuperTypes().stream().map(this::declaringType)
                .filter(parent -> parent != base && replaces(parent)).collect(Collectors.toList());
        if (!secondaries.isEmpty()) {
            if (base != null && replaces(base)) {
                result.addAll(contracts(base));
            }
            secondaries.forEach(parent -> result.addAll(contracts(parent)));
        }
        return result;
    }

    private String single(String name, CallableMethod method, boolean override) {
        var parameters = new ArrayList<String>();
        var arguments = new ArrayList<String>();
        arguments.add("this");
        for (CallableParameter parameter : method.getParameters()) {
            String local = parameterName(parameter);
            parameters.add(parameter.isVarArg() ? "..." + local + ": " + varArgType(parameter)
                    : local + ": " + parameterType(parameter));
            arguments.add(local);
        }
        String call = behavior(method) + "." + name + "(" + String.join(", ", arguments) + ")";
        boolean result = method.getReturnType().getKind() != CallableType.Kind.VOID;
        return documentation(method)
                + "    " + (override ? "override " : "") + name + "(" + String.join(", ", parameters) + "): "
                + resultType(method.getReturnType()) + " {\n"
                + "        " + (result ? "return " : "") + call + ";\n"
                + "    }";
    }

    /** Overloads differ in their number of parameters; the implementation dispatches on the argument count. */
    private String overloads(Type type, String name, List<CallableMethod> group, boolean override) {
        String modifier = override ? "override " : "";
        var text = new StringBuilder();
        var cases = new StringBuilder();
        var counts = new ArrayList<String>();
        for (CallableMethod method : group) {
            var parameters = new ArrayList<String>();
            var arguments = new ArrayList<String>();
            arguments.add("this");
            int index = 0;
            for (CallableParameter parameter : method.getParameters()) {
                String type0 = parameterType(parameter);
                parameters.add(parameterName(parameter) + ": " + type0);
                arguments.add("args[" + index++ + "] as " + type0);
            }
            text.append("    ").append(modifier).append(name).append("(").append(String.join(", ", parameters))
                    .append("): ").append(resultType(method.getReturnType())).append(";\n");
            counts.add(String.valueOf(method.getParameters().size()));
            String call = behavior(method) + "." + name + "(" + String.join(", ", arguments) + ")";
            cases.append("            case ").append(method.getParameters().size()).append(":\n")
                    .append(method.getReturnType().getKind() == CallableType.Kind.VOID
                            ? "                " + call + ";\n                return undefined;\n"
                            : "                return " + call + ";\n");
        }
        String expected = String.join(" or ", counts);
        return documentation(group.get(0)) + text
                + "    " + modifier + name + "(...args: unknown[]): unknown {\n"
                + "        switch (args.length) {\n"
                + cases
                + "            default:\n"
                + "                throw new TypeError(\"" + source.typeName(type) + "." + name + " takes " + expected
                + " arguments, not \" + args.length + \".\");\n"
                + "        }\n"
                + "    }";
    }

    private static String documentation(CallableMethod method) {
        return "/** Delegated to the behavior of " + method.getDeclaringType() + ". */\n";
    }

    /** The behavior that handles the method: bound methods use the declaring type's binding. */
    private String behavior(CallableMethod method) {
        String declaring = method.getDeclaringType();
        if (method.getJavaBinding().isBlank()) {
            String key = declaring + "." + method.getName();
            return "vmf.behaviorOf<" + interfaceName(declaring) + ">(this, " + TypeScriptRenderModel.quoted(key)
                    + ", vmf.unboundBehavior(" + TypeScriptRenderModel.quoted(key) + "))";
        }
        return "vmf.behaviorOf<" + interfaceName(declaring) + ">(this, " + TypeScriptRenderModel.quoted(declaring)
                + ", " + factory(declaring) + ")";
    }

    private Type declaringType(String name) {
        return model.findType(name).orElseThrow(() -> new IllegalArgumentException(
                "Cannot generate TypeScript behavior: " + name + " is not a model type"));
    }

    static String interfaceName(String declaring) {
        return declaring + "Behavior";
    }

    private static String factory(String declaring) {
        return "__vmfBehavior_" + declaring;
    }

    private static boolean sameSlot(CallableMethod left, CallableMethod right) {
        return left.getName().equals(right.getName()) && left.getParameters().size() == right.getParameters().size();
    }

    private static String checked(String name, Type owner) {
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*") || name.startsWith("__vmf")) {
            throw new IllegalArgumentException("Cannot generate TypeScript for " + owner.getQualifiedName() + "."
                    + name + ": name '" + name + "' must be a portable TypeScript identifier");
        }
        return name;
    }

    private static String parameterName(CallableParameter parameter) {
        String name = parameter.getName();
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("Cannot generate TypeScript: parameter '" + name
                    + "' must be a portable TypeScript identifier");
        }
        return RESERVED_WORDS.contains(name) || name.startsWith("__vmf") ? name + "_" : name;
    }

    /** A parameter type; arrays are read-only for the method. */
    private String parameterType(CallableParameter parameter) {
        return type(parameter.getType(), true);
    }

    private String varArgType(CallableParameter parameter) {
        return element(type(parameter.getType().getElementType(), true)) + "[]";
    }

    /** An array element type, in parentheses if it is a union. */
    private static String element(String type) {
        return type.contains(" ") ? "(" + type + ")" : type;
    }

    String resultType(CallableType type) {
        return type(type, false);
    }

    private String type(CallableType type, boolean parameter) {
        switch (type.getKind()) {
            case VOID:
                return "void";
            case VALUE: {
                ValueType value = type.getValueType();
                String name = source.typeName(value);
                return value.isPrimitive() || name.equals("unknown") ? name : name + " | null";
            }
            case ARRAY:
            case LIST:
                return (parameter ? "readonly " : "") + element(type(type.getElementType(), parameter)) + "[] | null";
            default:
                throw new IllegalStateException("Unsupported TypeScript callable type " + type.getKind());
        }
    }

    /** A delegated {@code toString()} replaces the object's string form. */
    private static boolean isToString(CallableMethod method) {
        return method.getName().equals("toString") && method.getParameters().isEmpty()
                && method.getReturnType().getKind() == CallableType.Kind.VALUE
                && method.getReturnType().getValueType().getKind() == ValueType.Kind.STRING;
    }
}
