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

import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Feature;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Checks the subset shared by portable model backends before opening any output files. */
public final class PortableCapabilities {
    private static final Set<String> RESERVED_WIRE_FIELDS = Set.of("@vmf-type", "@vmf-id");
    private static final Set<ValueType.Kind> TYPESCRIPT_TYPES = Set.of(ValueType.Kind.BOOLEAN, ValueType.Kind.INT8,
            ValueType.Kind.INT16, ValueType.Kind.INT32, ValueType.Kind.INT64, ValueType.Kind.CHAR, ValueType.Kind.FLOAT32,
            ValueType.Kind.FLOAT64, ValueType.Kind.STRING, ValueType.Kind.MODEL, ValueType.Kind.PARAMETER);
    private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString", "clone", "finalize");

    enum Profile {
        /** The TypeScript generator: the portable core contract without language-specific extras. */
        TYPESCRIPT,
        CSHARP
    }

    private PortableCapabilities() {
    }

    public static void validate(ResolvedModel model, String targetName) {
        validate(model, targetName, Profile.TYPESCRIPT);
    }

    static void validate(ResolvedModel model, String targetName, Profile profile) {
        var diagnostics = new ArrayList<String>();
        var typeIds = new HashMap<String, String>();
        if (model.getTypes().isEmpty()) {
            diagnostics.add(model.getNamespace() + " has no model types");
        }

        for (var type : model.getTypes()) {
            validateType(model, type, profile, diagnostics);
            if (!type.isInterfaceOnly()) {
                checkWireTypeId(type.getWireTypeName(), type, typeIds, diagnostics);
                checkWireTypeId(type.getQualifiedName(), type, typeIds, diagnostics);
            }
            checkWireFieldNames(type, diagnostics);
            if (profile == Profile.CSHARP) checkInheritedReferenceTypes(type, diagnostics);
            for (var property : type.getProperties()) {
                validateProperty(model, type, property, profile, diagnostics);
            }
        }
        if (!diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Unsupported declarations for the " + targetName + " target:\n - "
                    + String.join("\n - ", diagnostics));
        }
    }

    private static void validateType(ResolvedModel model, Type type, Profile profile, List<String> diagnostics) {
        String name = type.getQualifiedName();
        type.getEffectiveProperties().forEach(property -> validatePatternBindings(name, property, profile, diagnostics));
        if (type.isImmutable()) {
            if (type.isInterfaceOnly()) {
                diagnostics.add(name + " needs a concrete immutable declaration");
            } else {
                if (profile == Profile.TYPESCRIPT) {
                    checkTypeScriptImmutable(model, type, diagnostics);
                } else {
                    PortableInheritance.checkCSharpImmutableBases(model, type, diagnostics);
                }
                for (var property : type.getEffectiveProperties()) {
                    validateImmutableProperty(model, property, name + "." + property.getName(), diagnostics);
                }
            }
        } else if (profile == Profile.TYPESCRIPT) {
            for (String parent : type.getSuperTypes()) {
                model.findType(parent).filter(Type::isImmutable).ifPresent(immutable -> diagnostics.add(
                        name + " cannot extend the immutable type " + immutable.getQualifiedName()));
            }
        }
        if (profile == Profile.CSHARP && (!type.getEffectiveMethods().isEmpty() || type.getConstructionHook().isPresent())) {
            validateBehavior(model, type, diagnostics);
        }
        for (var feature : type.getFeatures()) {
            if (profile == Profile.CSHARP && feature == Feature.DELEGATION) continue;
            if (feature == Feature.CUSTOM_EQUALITY && profile == Profile.CSHARP && type.isStructuralEquality()) {
                continue;
            }
            // TypeScript has no language equality to configure; content equality is always generated.
            if (feature == Feature.CUSTOM_EQUALITY && profile == Profile.TYPESCRIPT) continue;
            diagnostics.add(name + " uses " + feature.getDescription());
        }
        if (type.getSuperTypeApplications().size() > 1) {
            if (profile == Profile.TYPESCRIPT) {
                // A TypeScript class extends one base class and implements the other supertypes; it declares the
                // properties of the implemented ones itself, so their behavior would not be inherited.
                checkTypeScriptSecondaries(model, type, diagnostics);
                checkTypeScriptComposition(model, type, diagnostics);
            } else {
                PortableInheritance.checkCSharpComposition(model, type, diagnostics);
            }
        }

        for (var parameter : type.getTypeParameters()) {
            if (profile == Profile.TYPESCRIPT && (parameter.isNumberBound() || parameter.getBound() == null)) {
                // A scalar parameter stays open in the interface-only class that declares it; concrete classes
                // bind it, so their documents know the scalar type.
                if (!type.isInterfaceOnly() || type.isImmutable()) {
                    diagnostics.add(name + "." + parameter.getName()
                            + " needs an interface-only type for a scalar type parameter");
                }
                continue;
            }
            if (profile == Profile.TYPESCRIPT && parameter.getBounds().size() == 1
                    && parameter.getBound().getKind() == ValueType.Kind.PARAMETER
                    && parameter.getBound().getModelType() != null) continue;
            if (profile == Profile.CSHARP && parameter.isNumberBound()) continue;
            if (profile == Profile.CSHARP && parameter.getBound() == null) {
                if (hasCompanion(model, type.getName()) && !acceptsScalarParameter(type, parameter.getName(), true)) {
                    diagnostics.add(name + "." + parameter.getName()
                            + " needs primary inheritance for an unbounded scalar parameter");
                }
                continue;
            }
            if (parameter.getBounds().size() != 1 || (parameter.getBound().getKind() != ValueType.Kind.MODEL
                    && !(profile == Profile.CSHARP && parameter.getBound().getKind() == ValueType.Kind.PARAMETER
                            && parameter.getBound().getModelType() != null))) {
                diagnostics.add(name + "." + parameter.getName() + " needs a single named model upper bound");
            }
        }

        for (var application : type.getSuperTypeApplications()) {
            var parent = model.findType(application.getModelType());
            if (parent.isEmpty()) {
                diagnostics.add(name + " needs a local model supertype");
                continue;
            }
            if (!supportsTypeArguments(model, type, parent.get(), application, profile)) {
                diagnostics.add(name + " has unsupported type arguments for " + parent.get().getQualifiedName());
            } else if (profile == Profile.CSHARP
                    && hasUnprojectableImmutableArgument(model, parent.get(), application)) {
                diagnostics.add(name + " needs scalar or ordinary list uses of an unbounded parameter, or a getter-only trait bound,"
                        + " to bind an immutable type argument for "
                        + parent.get().getQualifiedName());
            } else if (profile == Profile.CSHARP && hasUnprojectableGetterOnlyTraitArgument(model, parent.get(), application)) {
                diagnostics.add(name + " needs a named getter-only trait bound for a getter-only trait type argument to "
                        + parent.get().getQualifiedName());
            }
            checkOverrides(model, type, parent.get(), application, profile, diagnostics);
        }
    }

    /** A Java-only pattern needs one counterpart in the target's dialect: vmf:pattern:csharp or vmf:pattern:typescript. */
    private static void validatePatternBindings(String owner, Property property, Profile profile,
                                                List<String> diagnostics) {
        for (var constraint : property.getConstraints()) {
            if (constraint.isPortable()) continue;
            var bindings = constraint.getPatternBindings(profile == Profile.CSHARP ? "csharp" : "typescript");
            if (bindings.size() == 1 && !bindings.get(0).isBlank()) continue;
            diagnostics.add(owner + "." + property.getName() + (bindings.isEmpty() ? " uses a Java-only @Pattern"
                    : " needs one " + (profile == Profile.CSHARP ? "CLR" : "TypeScript")
                        + " pattern binding for each Java-only @Pattern"));
        }
    }

    private static void validateBehavior(ResolvedModel model, Type type, List<String> diagnostics) {
        String name = type.getQualifiedName();
        boolean canLeaveAbstractBinding = false;
        if (type.isImmutable()) {
            if (!type.getEffectiveMethods().isEmpty() && !type.getTypeParameters().isEmpty()) {
                diagnostics.add(name + " needs a nongeneric immutable type for C# behavior delegation");
            }
        } else {
            String root = primaryBehaviorRoot(model, type);
            var family = model.getTypes().stream().filter(candidate -> !candidate.isImmutable()
                    && primaryBehaviorRoot(model, candidate).equals(root)).collect(java.util.stream.Collectors.toList());
            canLeaveAbstractBinding = type.isInterfaceOnly() && (family.size() > 1
                    || model.getTypes().stream().anyMatch(member -> supportsBehaviorComposition(model, member)
                            && member.getSuperTypes().subList(1, member.getSuperTypes().size()).contains(type.getName())));
            if (family.stream().anyMatch(member -> member.getSuperTypes().size() > 1
                    && (!member.getEffectiveMethods().isEmpty() || member.getConstructionHook().isPresent())
                    && !supportsBehaviorComposition(model, member))) {
                diagnostics.add(name + " needs single primary inheritance for C# behavior delegation"
                        + " or supported rooted behavior roles and exact inherited callable diamonds"
                        + " or a bound primary chain with data-only secondary bases");
            }
            if ((family.stream().anyMatch(member -> supportsBehaviorComposition(model, member))
                    || (hasCompanion(model, type.getName()) && CSharpCapabilities.isBoundBehaviorRole(model, type)))
                    && hasSeparateLocalBehaviorBindings(type)
                    && !(CSharpCapabilities.hasDirectBoundBehaviorRoles(model, type)
                         && supportsBehaviorComposition(model, type))) {
                diagnostics.add(name + " needs a single local binding class in a composed behavior hierarchy");
            }
            if (family.stream().anyMatch(member -> supportsBoundBehaviorComposition(model, member)
                    || supportsPrimaryBehaviorWithDataSecondaries(model, member)) && hasSharedBehaviorBinding(model, type)) {
                diagnostics.add(name + " needs distinct Java binding classes for separate behavior declarations");
            }
            if (type.getConstructionHook().filter(hook -> hook.getJavaBinding().isBlank()).isPresent()) {
                diagnostics.add(name + " needs a source construction binding for C# behavior delegation");
            }
        }
        for (var method : type.getEffectiveMethods()) {
            String callable = name + "." + method.getName();
            if (method.getJavaBinding().isBlank() && !canLeaveAbstractBinding) {
                diagnostics.add(callable + " needs a source method binding for C# behavior delegation");
            }
            if (method.isStatic() || method.hasCheckedExceptions() || (OBJECT_METHODS.contains(method.getName())
                    && !supportsObjectOverride(model, type, method))) {
                diagnostics.add(callable + " needs an instance behavior method without checked exceptions or Object overrides");
            }
            var parameters = method.getParameters();
            if (!isCallableResult(model, type, method.getReturnType())
                    || parameters.stream().anyMatch(parameter -> !isCallableParameter(model, type, parameter.getType()))) {
                diagnostics.add(callable + " needs portable scalars, concrete models, closed model applications, "
                        + "explicit host type bindings, or arrays of these. Only arguments can use jagged arrays. Singular arguments and results "
                        + "also support named mutable abstract models. Results can also use List or VList of scalars, models, or Object");
            }
            if (!parameters.isEmpty() && parameters.get(parameters.size() - 1).isVarArg()
                    && parameters.subList(0, parameters.size() - 1).stream()
                            .anyMatch(parameter -> parameter.getType().getKind() == CallableType.Kind.ARRAY)) {
                diagnostics.add(callable + " needs a single array parameter when using variable arguments");
            }
        }
    }

    private static boolean supportsBehaviorComposition(ResolvedModel model, Type type) {
        if (supportsBoundBehaviorComposition(model, type) || supportsPrimaryBehaviorWithDataSecondaries(model, type)) return true;
        if (type.getSuperTypes().size() < 2 || !CSharpCapabilities.isUnboundBehaviorTrait(type)) return false;
        var seen = new HashSet<String>();
        for (String name : type.getSuperTypes()) {
            var parent = model.findType(name).orElse(null);
            if (parent == null || !parent.getSuperTypes().isEmpty()
                    || !CSharpCapabilities.isUnboundBehaviorTrait(parent)) return false;
            var methods = parent.getEffectiveMethods().stream().map(ResolvedModel.CallableMethod::getName)
                    .collect(java.util.stream.Collectors.toSet());
            if (methods.stream().anyMatch(seen::contains)) return false;
            seen.addAll(methods);
        }
        return type.getDeclaredMethods().stream().noneMatch(method -> seen.contains(method.getName()));
    }

    private static boolean supportsPrimaryBehaviorWithDataSecondaries(ResolvedModel model, Type type) {
        if (type.isImmutable() || type.getSuperTypes().size() < 2
                || (type.getEffectiveMethods().isEmpty() && type.getConstructionHook().isEmpty())
                || type.getEffectiveMethods().stream().anyMatch(method -> method.getJavaBinding().isBlank())) {
            return false;
        }
        for (Type primary = type; primary != null; primary = primary.getSuperTypes().isEmpty() ? null
                : model.findType(primary.getSuperTypes().get(0)).orElseThrow()) {
            if (!primary.getTypeParameters().isEmpty()) return false;
        }
        return PortableInheritance.hasClosedDataOnlySecondaries(model, type);
    }

    private static boolean supportsBoundBehaviorComposition(ResolvedModel model, Type type) {
        if (type.isImmutable() || !type.getTypeParameters().isEmpty() || type.getSuperTypes().size() < 2) {
            return false;
        }
        boolean canCompete = CSharpCapabilities.hasBoundBehaviorBranches(model, type);
        Map<String, Set<String>> origins = new HashMap<>();
        boolean hasBoundSecondary = false;
        for (int index = 0; index < type.getSuperTypes().size(); index++) {
            var parent = model.findType(type.getSuperTypes().get(index)).orElse(null);
            if (parent == null || parent.isImmutable() || !parent.getTypeParameters().isEmpty()) return false;
            if (index > 0 && (!parent.getEffectiveMethods().isEmpty() || parent.getConstructionHook().isPresent())) {
                boolean boundRoot = CSharpCapabilities.isBoundBehaviorRole(model, parent)
                        && (!type.isInterfaceOnly() || parent.isInterfaceOnly());
                if (canCompete && CSharpCapabilities.hasBoundBehaviorBranches(model, parent)) boundRoot = true;
                if (!boundRoot && !PortableInheritance.isInheritedBehaviorTrait(model, parent)) {
                    return false;
                }
                hasBoundSecondary = true;
            }
            var branch = parent.getEffectiveMethods().stream().collect(Collectors.groupingBy(ResolvedModel.CallableMethod::getName,
                    Collectors.mapping(ResolvedModel.CallableMethod::getDeclaringType, Collectors.toSet())));
            for (var method : branch.entrySet()) {
                var prior = origins.putIfAbsent(method.getKey(), method.getValue());
                if (!canCompete && prior != null && (!prior.equals(method.getValue()) || prior.stream().anyMatch(origin ->
                        !PortableInheritance.isAbstractBehaviorRole(model, origin)))) {
                    return false;
                }
            }
        }
        return hasBoundSecondary || type.getEffectiveMethods().stream().anyMatch(method ->
                PortableInheritance.isAbstractBehaviorRole(model, method.getDeclaringType()));
    }

    private static boolean hasSharedBehaviorBinding(ResolvedModel model, Type type) {
        var originByBinding = new HashMap<String, String>();
        type.getConstructionHook().ifPresent(hook -> originByBinding.put(hook.getJavaBinding(), hook.getDeclaringType()));
        var methods = new ArrayList<>(type.getEffectiveMethods());
        if (CSharpCapabilities.hasDirectBoundBehaviorRoles(model, type)) {
            // Include the losing declaration even if every callable that it declares was shadowed.
            type.getSuperTypes().stream().map(name -> model.findType(name).orElseThrow())
                    .forEach(parent -> methods.addAll(parent.getEffectiveMethods()));
        } else if (CSharpCapabilities.hasBoundBehaviorBranches(model, type)) {
            // Losing declarations stay available through other overloads or a composed parent.
            var pending = new ArrayList<>(type.getSuperTypes());
            var seen = new HashSet<String>();
            for (int index = 0; index < pending.size(); index++) {
                String name = pending.get(index);
                if (!seen.add(name)) continue;
                var parent = model.findType(name).orElseThrow();
                var localHook = parent.getConstructionHook().filter(hook -> hook.getDeclaringType().equals(name));
                if (localHook.isPresent()) {
                    String binding = localHook.get().getJavaBinding();
                    String origin = originByBinding.putIfAbsent(binding, name);
                    if (origin != null && !origin.equals(name)
                            && !shareBehaviorBinding(model, type, binding, origin, name)) return true;
                }
                methods.addAll(parent.getDeclaredMethods());
                pending.addAll(parent.getSuperTypes());
            }
        }
        for (var method : methods) {
            if (method.getJavaBinding().isBlank()) continue;
            String origin = originByBinding.putIfAbsent(method.getJavaBinding(), method.getDeclaringType());
            if (origin != null && !origin.equals(method.getDeclaringType())
                    && !shareBehaviorBinding(model, type, method.getJavaBinding(), origin, method.getDeclaringType())) {
                return true;
            }
        }
        return false;
    }

    private static boolean shareBehaviorBinding(ResolvedModel model, Type type, String binding,
                                                String first, String second) {
        for (Type owner = type; owner != null; owner = owner.getSuperTypes().isEmpty() ? null
                : model.findType(owner.getSuperTypes().get(0)).orElseThrow()) {
            var roles = CSharpCapabilities.sharedBehaviorBindings(model, owner).get(binding);
            if (roles == null) continue;
            String composition = owner.getName();
            return (roles.contains(first) || composition.equals(first))
                    && (roles.contains(second) || composition.equals(second));
        }
        return false;
    }

    private static boolean hasSeparateLocalBehaviorBindings(Type type) {
        var bindings = type.getDeclaredMethods().stream().map(ResolvedModel.CallableMethod::getJavaBinding)
                .filter(binding -> !binding.isBlank()).collect(java.util.stream.Collectors.toSet());
        type.getConstructionHook().filter(hook -> hook.getDeclaringType().equals(type.getName()))
                .map(ResolvedModel.ConstructionHook::getJavaBinding).ifPresent(bindings::add);
        return bindings.size() > 1;
    }

    private static boolean supportsObjectOverride(ResolvedModel model, Type type, ResolvedModel.CallableMethod method) {
        if (CSharpCapabilities.isObjectToString(type, method)) return supportsObjectToString(model, type);
        if (CSharpCapabilities.isObjectEquals(method) || CSharpCapabilities.isObjectHashCode(method)) {
            return supportsObjectEquality(model, type);
        }
        return false;
    }

    private static boolean supportsObjectEquality(ResolvedModel model, Type type) {
        if (type.isInterfaceOnly() || type.isStructuralEquality() || type.getConstructionHook().isPresent()) {
            return false;
        }
        if (type.isImmutable() && (!type.getSuperTypes().isEmpty()
                || model.getTypes().stream().anyMatch(candidate -> model.isSubtypeOf(candidate, type.getName())))) {
            return false;
        }
        Type root = type;
        while (root.getSuperTypes().size() == 1 && !root.isInterfaceOnly()) {
            root = model.findType(root.getSuperTypes().get(0)).orElseThrow();
        }
        if (!root.getSuperTypes().isEmpty() || root.isInterfaceOnly()) return false;
        String declaringType = root.getName();
        var methods = type.getEffectiveMethods();
        var equals = methods.stream().filter(CSharpCapabilities::isObjectEquals).findFirst().orElse(null);
        return equals != null && isUnboundObject(model, equals.getParameters().get(0).getType().getValueType())
                && methods.stream().anyMatch(CSharpCapabilities::isObjectHashCode)
                && methods.stream().allMatch(method -> method.getDeclaringType().equals(declaringType)
                        && method.getJavaBinding().equals(equals.getJavaBinding()));
    }

    private static boolean supportsObjectToString(ResolvedModel model, Type type) {
        if (type.isImmutable()) return true;
        while (type.getSuperTypes().size() == 1) type = model.findType(type.getSuperTypes().get(0)).orElseThrow();
        return type.getSuperTypes().isEmpty();
    }

    private static String primaryBehaviorRoot(ResolvedModel model, Type type) {
        while (!type.getSuperTypes().isEmpty()) {
            type = model.findType(type.getSuperTypes().get(0)).orElseThrow();
        }
        return type.getName();
    }

    private static boolean isCallableResult(ResolvedModel model, Type owner, CallableType type) {
        if (type.getKind() == CallableType.Kind.LIST) {
            var element = type.getElementType();
            // Java's delegated collection ABI uses a single named element type.
            return element.getKind() == CallableType.Kind.VALUE && element.getValueType().getArguments().isEmpty()
                    && isCallableResult(model, owner, element);
        }
        if (type.getKind() == CallableType.Kind.VALUE) {
            var value = type.getValueType();
            if (isUnboundObject(model, value)) return true;
            if (value.getKind() == ValueType.Kind.MODEL && value.getArguments().isEmpty()
                    && model.findType(value.getModelType()).filter(target -> target.isInterfaceOnly()
                            && !target.isImmutable() && !target.isGetterOnlyTrait()
                            && target.getTypeParameters().isEmpty()).isPresent()) return true;
        }
        return isCallableType(model, owner, type);
    }

    private static boolean isUnboundObject(ResolvedModel model, ValueType value) {
        return Object.class.getName().equals(value.getExternalType())
                && model.externalAnnotations(Object.class.getName()).isEmpty();
    }

    private static boolean isCallableParameter(ResolvedModel model, Type owner, CallableType type) {
        while (type.getKind() == CallableType.Kind.ARRAY && type.getElementType().getKind() == CallableType.Kind.ARRAY) {
            type = type.getElementType();
        }
        return type.getKind() == CallableType.Kind.VALUE ? isCallableResult(model, owner, type) : isCallableType(model, owner, type);
    }

    private static boolean isCallableType(ResolvedModel model, Type owner, CallableType type) {
        if (type.getKind() == CallableType.Kind.VOID) return true;
        var component = type.getKind() == CallableType.Kind.ARRAY ? type.getElementType() : type;
        if (component.getKind() != CallableType.Kind.VALUE) return false;
        var value = component.getValueType();
        if (value.isPortableScalar()) return true;
        if (value.getKind() == ValueType.Kind.EXTERNAL) {
            return supportsCSharpExternalType(model, value);
        }
        if (value.getKind() != ValueType.Kind.MODEL) return false;
        var target = model.findType(value.getModelType()).orElse(null);
        var arguments = value.getArguments();
        if (target == null || target.getTypeParameters().size() != arguments.size()) return false;
        if (arguments.isEmpty()) return !target.isInterfaceOnly();
        boolean closedArguments = arguments.stream().allMatch(argument -> argument.isPortableScalar()
                || argument.getKind() == ValueType.Kind.MODEL && argument.getArguments().isEmpty()
                    && model.findType(argument.getModelType())
                        .filter(declaration -> declaration.getTypeParameters().isEmpty() && !declaration.isGetterOnlyTrait()).isPresent());
        return closedArguments && supportsTypeArguments(model, owner, target, value, Profile.CSHARP)
                && !hasUnprojectableImmutableArgument(model, target, value);
    }

    private static boolean hasUnprojectableImmutableArgument(ResolvedModel model, Type parent, ValueType application) {
        for (int i = 0; i < application.getArguments().size(); i++) {
            if (!isImmutableType(model, application.getArguments().get(i))) continue;
            var parameter = parent.getTypeParameters().get(i);
            var bound = parameter.getBound();
            if (bound == null) {
                if (!acceptsScalarParameter(parent, parameter.getName(), false)) return true;
            } else if (!isGetterOnlyTrait(model, bound) && !isImmutableType(model, bound)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUnprojectableGetterOnlyTraitArgument(ResolvedModel model, Type parent, ValueType application) {
        for (int index = 0; index < application.getArguments().size(); index++) {
            var argument = application.getArguments().get(index);
            if (argument.getKind() != ValueType.Kind.MODEL || !isGetterOnlyTrait(model, argument)) continue;
            var bound = parent.getTypeParameters().get(index).getBound();
            // The native class and its read-only view both implement the same named getter contract.
            if (!argument.getArguments().isEmpty() || bound == null || bound.getKind() != ValueType.Kind.MODEL
                    || !bound.getArguments().isEmpty() || !isGetterOnlyTrait(model, bound)) return true;
        }
        return false;
    }

    private static boolean acceptsScalarParameter(Type parent, String parameter, boolean allowMaps) {
        for (var property : parent.getEffectiveProperties()) {
            if (allowMaps && property.getKind() == Property.Kind.MAP) continue;
            if (usesParameter(property.getKeyType(), parameter)) return false;
            if (!usesParameter(property.getValueType(), parameter)) continue;
            if (property.getKind() != Property.Kind.SCALAR
                    || (property.isCollection() && (!property.isOrdered() || property.isUnique()
                            || property.getUpperBound() >= 0 || !property.allowsNullElements()))) return false;
        }
        return true;
    }

    private static boolean usesParameter(ValueType use, String parameter) {
        return use != null && use.getKind() == ValueType.Kind.PARAMETER && parameter.equals(use.getParameterName());
    }

    private static boolean supportsTypeArguments(ResolvedModel model, Type owner, Type parent, ValueType application,
                                                 Profile profile) {
        var arguments = application.getArguments();
        var parameters = parent.getTypeParameters();
        if (arguments.size() != parameters.size()) return false;
        for (int i = 0; i < arguments.size(); i++) {
            var argument = arguments.get(i);
            var parameter = parameters.get(i);
            if (parameter.isNumberBound()) {
                if (!argument.isNumericScalar() && !isNumberParameter(owner, argument)) return false;
            } else if (profile == Profile.CSHARP && parameter.getBound() == null) {
                if (!isScalarSlot(owner, argument) && !isImmutableType(model, argument)
                        && !supportsCSharpExternalType(model, argument)) return false;
            } else if (profile == Profile.TYPESCRIPT && parameter.getBound() == null) {
                // An immutable value can fill a scalar slot: a single value or an ordinary list.
                if (!isScalarSlot(owner, argument) && !(isImmutableType(model, argument)
                        && acceptsScalarParameter(parent, parameter.getName(), false))) return false;
            } else if (argument.getModelType() == null) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnboundedParameter(Type owner, ValueType valueType) {
        return valueType.getKind() == ValueType.Kind.PARAMETER && owner.getTypeParameters().stream()
                .anyMatch(parameter -> parameter.getBound() == null && parameter.getName().equals(valueType.getParameterName()));
    }

    private static boolean isNumberParameter(Type owner, ValueType valueType) {
        return valueType.getKind() == ValueType.Kind.PARAMETER && owner.getTypeParameters().stream()
                .anyMatch(parameter -> parameter.isNumberBound() && parameter.getName().equals(valueType.getParameterName()));
    }

    private static boolean isScalarSlot(Type owner, ValueType valueType) {
        return valueType.isPortableScalar() || isNumberParameter(owner, valueType) || isUnboundedParameter(owner, valueType);
    }

    private static void checkOverrides(ResolvedModel model, Type type, Type parent, ValueType application, Profile profile,
                                       List<String> diagnostics) {
        for (var property : type.getProperties()) {
            var inherited = findProperty(parent, property.getName());
            if (inherited.isEmpty()) continue;
            if (profile == Profile.TYPESCRIPT
                    && supportsTypeScriptRedeclaration(model, type, parent, application, property, inherited.get())) continue;
            if (profile == Profile.CSHARP && type.isImmutable() && !type.isInterfaceOnly()
                    && parent.isGetterOnlyTrait() && inherited.get().isGetterOnly()
                    && sameScalarSlot(property, inherited.get())) continue;
            if (profile == Profile.CSHARP && implementsObjectAlternativeGetter(model, type, property)) continue;
            if (profile == Profile.CSHARP && type.getSuperTypes().size() == 1
                    && (!type.isInterfaceOnly() || property.isGetterOnly() == inherited.get().isGetterOnly())
                    && (sameScalarSlot(property, inherited.get())
                        || PortableInheritance.sameStorageSlot(property, inherited.get(), parent, application)
                        || CSharpCapabilities.redeclaresInheritedManagedCollectionDefault(model, type, property)
                        || (CSharpCapabilities.narrowsObjectGetter(property, inherited.get())
                            && supportsObjectGetter(model, parent, inherited.get()))
                        || supportsCovariantReference(model, type, parent, property, inherited.get())
                        || supportsRelationshipGetter(model, type, parent, property, inherited.get()))) continue;
            if (profile == Profile.CSHARP && type.getSuperTypes().size() > 1
                    && (PortableInheritance.sameInheritedContract(type, property, inherited.get(), parent, application)
                        || supportsCovariantTraitGetter(model, type, parent, property, inherited.get())
                        || (CSharpCapabilities.narrowsObjectGetter(property, inherited.get())
                            && supportsObjectGetter(model, parent, inherited.get())))) continue;
            diagnostics.add(type.getQualifiedName() + "." + property.getName() + " redeclares an inherited property");
        }
    }

    /**
     * The redeclarations a TypeScript class hierarchy can represent: the same storage again (a scalar may change its
     * literal default), a getter-only reference narrowed by a getter-only trait or by a subtype of an interface-only
     * type, a getter-only reference that each implementation declares as its containment or container end, and on
     * several supertypes the same inherited contract or a narrowed trait getter.
     */
    private static boolean supportsTypeScriptRedeclaration(ResolvedModel model, Type type, Type parent,
                                                           ValueType application, Property property, Property inherited) {
        if (type.isImmutable() && !type.isInterfaceOnly() && parent.isGetterOnlyTrait() && inherited.isGetterOnly()
                && sameScalarSlot(property, inherited)) return true;
        if (type.getSuperTypes().size() > 1) {
            return PortableInheritance.sameInheritedContract(type, property, inherited, parent, application)
                    || supportsCovariantTraitGetter(model, type, parent, property, inherited);
        }
        if (type.isInterfaceOnly() && property.isGetterOnly() != inherited.isGetterOnly()) return false;
        if (sameScalarSlot(property, inherited) || PortableInheritance.sameStorageSlot(property, inherited, parent, application)) {
            return true;
        }
        return supportsCovariantReference(model, type, parent, property, inherited)
                || supportsRelationshipGetter(model, type, parent, property, inherited);
    }

    /**
     * The supertypes a TypeScript class implements besides its base class contribute only data: their methods and
     * construction hooks would not run for it.
     */
    private static void checkTypeScriptSecondaries(ResolvedModel model, Type type, List<String> diagnostics) {
        var chain = new HashSet<String>();
        for (Type base = typeScriptBase(model, type); base != null; base = typeScriptBase(model, base)) {
            chain.add(base.getName());
        }
        var pending = new java.util.ArrayDeque<>(type.getSuperTypes());
        var seen = new HashSet<String>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (chain.contains(name) || !seen.add(name)) continue;
            var secondary = model.findType(name).orElse(null);
            if (secondary == null) continue;
            if (!secondary.isInterfaceOnly()
                    && (!secondary.getEffectiveMethods().isEmpty() || secondary.getConstructionHook().isPresent())) {
                diagnostics.add(type.getQualifiedName() + " needs a data-only secondary supertype instead of "
                        + secondary.getQualifiedName());
            }
            pending.addAll(secondary.getSuperTypes());
        }
    }

    /** The base class of a TypeScript class, as the TypeScript generator chooses it. */
    private static Type typeScriptBase(ResolvedModel model, Type type) {
        Type firstInterfaceOnly = null;
        for (String name : type.getSuperTypes()) {
            Type parent = model.findType(name).orElse(null);
            if (parent == null) continue;
            if (type.isGetterOnlyTrait()) return parent;
            if (parent.isGetterOnlyTrait()) continue;
            if (type.isImmutable()) {
                if (parent.isImmutable()) return parent;
            } else if (!parent.isInterfaceOnly()) {
                return parent;
            } else if (firstInterfaceOnly == null) {
                firstInterfaceOnly = parent;
            }
        }
        return type.isImmutable() ? null : firstInterfaceOnly;
    }

    static boolean projectsRelationshipGetter(ResolvedModel model, Type owner, Property property) {
        if (owner.getSuperTypes().size() != 1) return false;
        Type parent = model.findType(owner.getSuperTypes().get(0)).orElseThrow();
        return findProperty(parent, property.getName()).filter(base ->
                supportsRelationshipGetter(model, owner, parent, property, base)).isPresent();
    }

    private static boolean supportsRelationshipGetter(ResolvedModel model, Type owner, Type base,
                                                      Property property, Property getter) {
        if (!base.isInterfaceOnly() || base.isGetterOnlyTrait() || !base.getTypeParameters().isEmpty()
                || !getter.getDeclaringType().equals(base.getName()) || !getter.isGetterOnly()
                || getter.getKind() != Property.Kind.REFERENCE || !getter.getOppositeName().isEmpty()
                || getter.getValueType().getKind() != ValueType.Kind.MODEL
                || !getter.getValueType().getArguments().isEmpty()
                || getter.hasDeclaredDefault() || !getter.getConstraints().isEmpty()
                || base.getSuperTypes().stream().map(parent -> model.findType(parent).orElseThrow())
                        .anyMatch(parent -> findProperty(parent, getter.getName()).isPresent())
                || !isRelationshipGetterImplementation(owner, base, property, getter)) return false;

        return model.getTypes().stream().filter(candidate -> candidate != base
                        && assignable(model, candidate, base.getName()))
                .allMatch(candidate -> candidate.getProperties().stream()
                        .anyMatch(current -> isRelationshipGetterImplementation(candidate, base, current, getter)));
    }

    private static boolean isRelationshipGetterImplementation(Type type, Type base, Property property,
                                                               Property getter) {
        if (type.isInterfaceOnly() || type.isImmutable() || !type.getTypeParameters().isEmpty()
                || !type.getSuperTypes().equals(List.of(base.getName())) || !property.getName().equals(getter.getName())
                || property.isGetterOnly() || property.getOppositeName().isEmpty() || property.isSharedContainer()
                || property.getValueType().getKind() != ValueType.Kind.MODEL
                || !property.getValueType().getArguments().isEmpty()
                || !property.getValueType().getModelType().equals(getter.getValueType().getModelType())
                || !property.getWireName().equals(getter.getWireName())
                || property.hasDeclaredDefault() || !property.getConstraints().isEmpty()
                || property.getLowerBound() != getter.getLowerBound() || property.getUpperBound() != getter.getUpperBound()) {
            return false;
        }
        return getter.isCollection() ? property.isCollection() && property.getKind() == Property.Kind.CONTAINMENT
                && getter.isOrdered() && property.isOrdered() && !getter.isUnique() && !property.isUnique()
                && property.getEntryKey().isEmpty()
                : !property.isCollection() && property.getKind() == Property.Kind.CONTAINER;
    }

    private static void checkInheritedReferenceTypes(Type type, List<String> diagnostics) {
        var targetByProperty = new HashMap<String, String>();
        for (Property property : type.getEffectiveProperties()) {
            if (!isUnidirectionalNamedReference(property)) continue;
            String target = property.getValueType().getModelType();
            String previous = targetByProperty.putIfAbsent(property.getName(), target);
            if (previous != null && !previous.equals(target)) {
                diagnostics.add(type.getQualifiedName() + "." + property.getName()
                        + " inherits different model targets; explicitly redeclare the narrower getter");
            }
        }
    }

    static boolean declaresObjectAlternativeGetter(ResolvedModel model, Type owner, Property getter) {
        if (!owner.isInterfaceOnly() || owner.isGetterOnlyTrait() || owner.isImmutable()
                || !owner.getTypeParameters().isEmpty() || !CSharpCapabilities.isObjectGetter(getter)
                || !getter.getDeclaringType().equals(owner.getName())
                || !model.externalAnnotations(Object.class.getName()).isEmpty()
                || owner.getSuperTypes().stream().map(name -> model.findType(name).orElseThrow())
                        .anyMatch(parent -> findProperty(parent, getter.getName()).isPresent())) return false;

        var descendants = model.getTypes().stream().filter(candidate -> candidate != owner
                && assignable(model, candidate, owner.getName())).collect(java.util.stream.Collectors.toList());
        return !descendants.isEmpty() && descendants.stream().allMatch(type ->
                !type.isInterfaceOnly() && !type.isImmutable() && type.getTypeParameters().isEmpty()
                        && type.getSuperTypes().equals(List.of(owner.getName()))
                        && type.getProperties().stream().anyMatch(property -> isObjectAlternative(property, getter)));
    }

    static boolean implementsObjectAlternativeGetter(ResolvedModel model, Type type, Property property) {
        if (type.getSuperTypes().size() != 1 || type.isInterfaceOnly() || type.isImmutable()) return false;
        Type owner = model.findType(type.getSuperTypes().get(0)).orElseThrow();
        return owner.getProperties().stream().anyMatch(getter ->
                isObjectAlternative(property, getter) && declaresObjectAlternativeGetter(model, owner, getter));
    }

    private static boolean isObjectAlternative(Property property, Property getter) {
        if (!CSharpCapabilities.isObjectGetter(getter) || !property.getName().equals(getter.getName())
                || property.isCollection() || property.isGetterOnly() || !property.getConstraints().isEmpty()
                || property.getLowerBound() != 0 || property.getUpperBound() != 1
                || property.getLowerBound() != getter.getLowerBound() || property.getUpperBound() != getter.getUpperBound()
                || !property.getWireName().equals(getter.getWireName())) return false;
        if (CSharpCapabilities.narrowsObjectGetter(property, getter)) return true;
        if (property.hasDeclaredDefault()) return false;
        var type = property.getValueType();
        return property.getKind() == Property.Kind.SCALAR && Object.class.getName().equals(type.getExternalType())
                || property.getKind() == Property.Kind.REFERENCE && property.getOppositeName().isEmpty()
                    && type.getKind() == ValueType.Kind.MODEL && type.getArguments().isEmpty();
    }

    private static boolean supportsObjectGetter(ResolvedModel model, Type owner, Property property) {
        if (!owner.isGetterOnlyTrait() || !owner.getSuperTypes().isEmpty() || !owner.getTypeParameters().isEmpty()
                || !owner.getEffectiveMethods().isEmpty() || owner.getConstructionHook().isPresent()
                || !CSharpCapabilities.isObjectGetter(property) || !model.externalAnnotations(Object.class.getName()).isEmpty()) return false;

        var descendants = model.getTypes().stream().filter(candidate -> candidate != owner
                && assignable(model, candidate, owner.getName())).collect(java.util.stream.Collectors.toList());
        return !descendants.isEmpty() && descendants.stream().allMatch(candidate ->
                !candidate.isInterfaceOnly() && !candidate.isImmutable()
                        && candidate.getSuperTypes().contains(owner.getName())
                        && candidate.getSuperTypes().stream().filter(parent -> !parent.equals(owner.getName()))
                            .map(parent -> model.findType(parent).orElseThrow())
                            .noneMatch(parent -> findProperty(parent, property.getName()).isPresent())
                        && candidate.getProperties().stream().anyMatch(narrow -> CSharpCapabilities.narrowsObjectGetter(narrow, property)));
    }

    private static boolean sameScalarSlot(Property property, Property inherited) {
        var type = property.getValueType();
        var inheritedType = inherited.getValueType();
        return !property.isCollection() && !inherited.isCollection()
                && property.getKind() == Property.Kind.SCALAR && inherited.getKind() == Property.Kind.SCALAR
                && type.isPortableScalar() && type.getKind() == inheritedType.getKind()
                && type.isPrimitive() == inheritedType.isPrimitive()
                && property.getLowerBound() == inherited.getLowerBound()
                && property.getUpperBound() == inherited.getUpperBound();
    }

    private static boolean supportsCovariantReference(ResolvedModel model, Type owner, Type parent,
                                                       Property property, Property inherited) {
        if (supportsCovariantTraitGetter(model, owner, parent, property, inherited)) return true;
        if (!isUnidirectionalNamedReference(property) || !isUnidirectionalNamedReference(inherited)
                || property.getLowerBound() != inherited.getLowerBound()
                || property.getUpperBound() != inherited.getUpperBound()) return false;
        // The native owner storage and projection must follow one simple class hierarchy.
        for (Type base = owner; base != null; base = firstModelParent(model, base)) {
            if (!base.getTypeParameters().isEmpty() || base.getSuperTypes().size() > 1
                    || hasCompanion(model, base.getName())) return false;
        }
        String narrowName = property.getValueType().getModelType();
        String broadName = inherited.getValueType().getModelType();
        Type narrow = model.findType(narrowName).orElse(null);
        Type broad = model.findType(broadName).orElse(null);
        if (narrow == null || broad == null || narrow.isGetterOnlyTrait() || broad.isGetterOnlyTrait()
                || hasCompanion(model, narrowName) || hasCompanion(model, broadName)) return false;
        if (narrow == broad) {
            // Java needs an explicit reaffirmation after narrowing to avoid inheriting both Java getters.
            for (Type base = firstModelParent(model, parent); base != null; base = firstModelParent(model, base)) {
                var earlier = findProperty(base, property.getName());
                if (earlier.filter(PortableCapabilities::isUnidirectionalNamedReference)
                        .filter(candidate -> !narrowName.equals(candidate.getValueType().getModelType())).isPresent()) {
                    return true;
                }
            }
            return false;
        }
        if (owner.isImmutable()
                ? !(parent.isImmutable() || parent.isGetterOnlyTrait() && inherited.isGetterOnly())
                : !(parent.isInterfaceOnly() && inherited.isGetterOnly())) return false;
        for (Type base = primaryBase(model, narrow); base != null; base = primaryBase(model, base)) {
            if (base == broad) return true;
        }
        return false;
    }

    static boolean supportsCovariantTraitGetter(ResolvedModel model, Type owner, Type parent,
                                                 Property property, Property inherited) {
        if (!owner.isGetterOnlyTrait() || !parent.isGetterOnlyTrait() || !owner.getEffectiveMethods().isEmpty()
                || owner.getConstructionHook().isPresent() || !property.isGetterOnly() || !inherited.isGetterOnly()
                || !isUnidirectionalNamedReference(property) || !isUnidirectionalNamedReference(inherited)
                || !PortableInheritance.sameCovariantGetterMetadata(property, inherited)) return false;
        Type narrow = model.findType(property.getValueType().getModelType()).orElse(null);
        Type broad = model.findType(inherited.getValueType().getModelType()).orElse(null);
        if (narrow == null || broad == null || narrow.isInterfaceOnly() || broad.isInterfaceOnly()
                || !narrow.getTypeParameters().isEmpty() || !broad.getTypeParameters().isEmpty()
                || narrow.isImmutable() || broad.isImmutable()) return false;
        if (hasCompanion(model, broad.getName())) return assignable(model, narrow, broad.getName());
        for (Type base = narrow; base != null; base = primaryBase(model, base)) {
            if (base == broad) return true;
        }
        return false;
    }

    private static boolean isUnidirectionalNamedReference(Property property) {
        var type = property.getValueType();
        return property.getKind() == Property.Kind.REFERENCE && !property.isCollection()
                && property.getOppositeName().isEmpty() && !property.hasDeclaredDefault()
                && type.getKind() == ValueType.Kind.MODEL && type.getArguments().isEmpty();
    }

    private static Type primaryBase(ResolvedModel model, Type type) {
        Type parent = firstModelParent(model, type);
        if (parent == null) return null;
        return !type.isImmutable() || parent.isImmutable() ? parent : null;
    }

    private static Type firstModelParent(ResolvedModel model, Type type) {
        return type.getSuperTypes().isEmpty() ? null : model.findType(type.getSuperTypes().get(0)).orElse(null);
    }

    private static void checkWireTypeId(String id, Type type, Map<String, String> knownIds, List<String> diagnostics) {
        if (id.isBlank()) {
            diagnostics.add(type.getQualifiedName() + " has an empty wire type name");
            return;
        }
        String previous = knownIds.putIfAbsent(id, type.getQualifiedName());
        if (previous != null && !previous.equals(type.getQualifiedName())) {
            diagnostics.add(type.getQualifiedName() + " and " + previous + " share wire type name '" + id + "'");
        }
    }

    private static void checkWireFieldNames(Type type, List<String> diagnostics) {
        var names = new HashSet<String>();
        for (var property : type.getEffectiveProperties()) {
            String name = property.getWireName();
            if (name.isBlank() || RESERVED_WIRE_FIELDS.contains(name)) {
                diagnostics.add(type.getQualifiedName() + "." + property.getName()
                        + " has an empty or reserved wire field name '" + name + "'");
            } else if (!names.add(name)) {
                diagnostics.add(type.getQualifiedName() + "." + property.getName()
                        + " duplicates wire field name '" + name + "'");
            }
        }
    }

    private static void validateProperty(ResolvedModel model, Type owner, Property property, Profile profile,
                                         List<String> diagnostics) {
        String name = owner.getQualifiedName() + "." + property.getName();
        boolean executableDefault = profile == Profile.CSHARP && CSharpCapabilities.bindsExecutableDefault(property);
        boolean collectionDefault = profile == Profile.CSHARP && CSharpCapabilities.bindsExecutableCollectionDefault(property);
        boolean literalCollectionDefault = profile == Profile.CSHARP && property.getCollectionDefault().isPresent()
                && (supportsLiteralCollectionDefault(model, owner, property)
                    || (CSharpCapabilities.redeclaresInheritedManagedCollectionDefault(model, owner, property)
                        && encodesLiteralCollectionDefault(property))
                    || supportsManagedDefaultWithReferences(model, owner, property));
        boolean referenceDefault = profile == Profile.CSHARP && CSharpCapabilities.hasReferenceSetDefault(property)
                && supportsReferenceSetDefault(model, owner, property);
        if (profile == Profile.CSHARP && !property.getAnnotationValues(CSharpCapabilities.EXECUTABLE_DEFAULT_ANNOTATION).isEmpty()
                && !(executableDefault && supportsExecutableScalarDefault(model, owner)
                    || collectionDefault && (!owner.isInterfaceOnly() && !participatesInInheritance(model, owner)
                        && owner.getConstructionHook().isEmpty()
                        || CSharpCapabilities.stagesExecutableCollectionDefaults(model, owner)
                        || CSharpCapabilities.supportsInheritedExecutableCollectionDefaults(model, owner)
                        || supportsManagedDefaultWithReferences(model, owner, property))
                    || referenceDefault && CSharpCapabilities.bindsReferenceSetDefault(property))) {
            diagnostics.add(name + " needs a nonliteral scalar default on a concrete C# declaration with primary inheritance"
                    + " or immutable getter-only data traits"
                    + " or on a getter-only data trait used by concrete immutable scalar models"
                    + " or a managed scalar collection default on an independent concrete declaration or supported primary family"
                    + " or an unopposed identity reference-set default on an independent concrete declaration without a construction hook"
                    + " or an unchanged reference-set or managed collection slot on a concrete mutable reference-default model or its primary hierarchy");
        }
        for (var feature : property.getFeatures()) {
            if (profile == Profile.CSHARP && feature == Feature.SYNCHRONIZATION) {
                diagnostics.add(name + " declares @SyncWith, which is inactive in the Java generator");
                continue;
            }
            if (profile == Profile.CSHARP && feature == Feature.MAP) continue;
            if (profile == Profile.CSHARP && feature == Feature.KEYED) {
                var key = property.getEntryKey().orElseThrow().getValueType();
                var element = property.getValueType();
                boolean concreteEntry = element.getKind() == ValueType.Kind.MODEL && !containsTypeParameter(element);
                boolean genericEntry = isBoundKeyedEntry(model, owner, property, key);
                if ((!concreteEntry && !genericEntry) || containsTypeParameter(key)
                        || (!key.isPortableScalar() && key.getKind() != ValueType.Kind.MODEL)) {
                    diagnostics.add(name + " needs concrete entry and key types for keyed containment"
                            + " or an unopposed ordered collection with a named mutable model bound and a scalar or named model key");
                }
                continue;
            }
            if (profile == Profile.CSHARP && feature == Feature.DEFAULT_VALUE && property.getScalarDefault().isPresent()) {
                Object value = property.getScalarDefault().orElseThrow().getValue();
                if (value instanceof String && !StandardCharsets.UTF_8.newEncoder().canEncode((String) value)) {
                    diagnostics.add(name + " has a string default with an unpaired UTF-16 surrogate");
                }
                continue;
            }
            if ((executableDefault || collectionDefault || literalCollectionDefault || referenceDefault)
                    && feature == Feature.DEFAULT_VALUE) continue;
            if (profile == Profile.TYPESCRIPT && feature == Feature.DEFAULT_VALUE
                    && property.getKind() == Property.Kind.SCALAR && !property.isCollection()
                    && property.getScalarDefault().isPresent()) continue;
            // Both targets check declared value constraints; validatePatternBindings covers Java-only patterns.
            if (feature == Feature.VALUE_CONSTRAINT) continue;
            if (feature == Feature.READ_ONLY && property.isSharedContainer()) continue;
            diagnostics.add(name + " uses " + feature.getDescription());
        }

        if (property.isGetterOnly() && !owner.isImmutable() && !owner.isInterfaceOnly()
                && !property.getFeatures().contains(Feature.READ_ONLY)) {
            diagnostics.add(name + " uses " + Feature.READ_ONLY.getDescription());
        }

        ValueType valueType = property.getValueType();
        boolean external = profile == Profile.CSHARP && property.getKind() == Property.Kind.SCALAR
                && supportsCSharpExternalType(model, valueType)
                && (!property.isCollection() || property.isOrdered() && !property.isUnique()
                    && (property.getUpperBound() < 0 || property.allowsNullElements()));
        boolean objectGetter = profile == Profile.CSHARP && (supportsObjectGetter(model, owner, property)
                || declaresObjectAlternativeGetter(model, owner, property));
        boolean objectValue = profile == Profile.CSHARP && !owner.isImmutable() && !property.isGetterOnly()
                && property.getKind() == Property.Kind.SCALAR
                && isUnboundObject(model, valueType) && !property.hasDeclaredDefault()
                && property.getConstraints().isEmpty()
                && (property.isCollection()
                    ? property.isOrdered() && !property.isUnique() && property.allowsNullElements()
                    : property.getLowerBound() == 0 && property.getUpperBound() == 1);
        if (valueType.getKind() == ValueType.Kind.EXTERNAL && !external && !objectGetter && !objectValue
                || (profile == Profile.TYPESCRIPT && !TYPESCRIPT_TYPES.contains(valueType.getKind()))) {
            diagnostics.add(name + " has unsupported type " + valueType.getSourceType());
        }

        switch (property.getKind()) {
            case SCALAR:
                if (property.isCollection()) {
                    if (profile == Profile.TYPESCRIPT) {
                        if (!property.isOrdered() || property.isUnique()) {
                            diagnostics.add(name + " needs an ordered scalar list");
                        }
                    } else if (!external && !objectValue && !isScalarSlot(owner, valueType)) {
                        diagnostics.add(name + " needs a concrete portable scalar element type");
                    }
                } else if (!isSupportedSingularBound(property, profile)) {
                    diagnostics.add(name + " needs a scalar with upper bound 1");
                }
                break;
            case MAP:
                if (profile == Profile.CSHARP
                        && (!isScalarSlot(owner, property.getKeyType()) || !isScalarSlot(owner, valueType))) {
                    diagnostics.add(name + " needs concrete portable scalar map key and value types");
                }
                break;
            case REFERENCE:
                if (!isSupportedReference(property, profile)) {
                    String forms = profile == Profile.CSHARP
                            ? "a single-valued reference, an ordered reference sequence, or an identity reference set"
                            : "a single-valued reference, an ordered reference list, or an unbounded unordered identity"
                                + " reference set without an opposite";
                    diagnostics.add(name + " needs " + forms);
                }
                // Immutable owners report unordered collections in checkTypeScriptImmutable.
                if (profile == Profile.TYPESCRIPT && !owner.isImmutable() && property.isCollection()
                        && !property.isOrdered()
                        && (isImmutableType(model, valueType) || isGetterOnlyTrait(model, valueType))) {
                    diagnostics.add(name + " needs an ordered list for immutable values");
                }
                if (!property.getOppositeName().isEmpty()) {
                    checkOpposite(model, owner, property, Property.Kind.REFERENCE, profile, name, diagnostics);
                    // A getter-only trait can be the other end when every implementation is mutable: each one
                    // declares the reciprocal reference itself.
                    if (profile == Profile.TYPESCRIPT && (isImmutableType(model, valueType)
                            || isGetterOnlyTrait(model, valueType) && !hasOnlyMutableImplementations(model, valueType))) {
                        diagnostics.add(name + " needs a mutable model type for a reference with an opposite");
                    }
                }
                break;
            case CONTAINMENT:
                if (valueType.getModelType() == null || !isSupportedContainment(property, profile)) {
                    String forms = profile == Profile.CSHARP
                            ? "a single-valued containment, an ordered collection, or an identity set"
                            : "a single-valued containment or an ordered collection of model objects";
                    diagnostics.add(name + " needs " + forms);
                }
                if (isImmutableType(model, valueType)) {
                    diagnostics.add(name + " cannot contain an immutable value");
                }
                // A getter-only trait can own children when every implementation is mutable.
                if (isGetterOnlyTrait(model, valueType) && !supportsOwningGetterRole(model, property)) {
                    diagnostics.add(name + " cannot use a getter-only trait as a containment target");
                }
                if (!property.getOppositeName().isEmpty()) {
                    checkOpposite(model, owner, property, Property.Kind.CONTAINER, profile, name, diagnostics);
                }
                break;
            case CONTAINER:
                // A required container (lower bound 1) is reported by validation while the object is uncontained.
                if (property.isCollection() || !isSupportedSingularBound(property, profile)) {
                    diagnostics.add(name + " needs a single-valued container");
                }
                if (property.isSharedContainer()) {
                    if (valueType.getKind() != ValueType.Kind.MODEL || !valueType.getArguments().isEmpty()
                            || isImmutableType(model, valueType) || isGetterOnlyTrait(model, valueType)) {
                        diagnostics.add(name + " needs a named mutable model as its shared container type");
                    }
                } else {
                    checkOpposite(model, owner, property, Property.Kind.CONTAINMENT, profile, name, diagnostics);
                }
                break;
            default:
                throw new IllegalStateException("Unknown property kind " + property.getKind());
        }
    }

    private static boolean supportsOwningGetterRole(ResolvedModel model, Property property) {
        var use = property.getValueType();
        Type role = model.findType(use.getModelType()).orElseThrow();
        if (!role.getTypeParameters().isEmpty() || !use.getArguments().isEmpty()
                || (!property.getOppositeName().isEmpty() && use.getKind() != ValueType.Kind.MODEL)) return false;
        var implementations = model.getTypes().stream().filter(type -> !type.isInterfaceOnly()
                && assignable(model, type, role.getName())).collect(java.util.stream.Collectors.toList());
        return !implementations.isEmpty() && implementations.stream().noneMatch(Type::isImmutable);
    }


    private static void validateImmutableProperty(ResolvedModel model, Property property, String name,
                                                  List<String> diagnostics) {
        if (property.getKind() == Property.Kind.CONTAINMENT || property.getKind() == Property.Kind.CONTAINER) {
            diagnostics.add(name + " cannot participate in containment on an immutable owner");
        } else if (property.getKind() == Property.Kind.REFERENCE && !isImmutableType(model, property.getValueType())) {
            diagnostics.add(name + " cannot reference a mutable model from an immutable owner");
        }
    }

    private static boolean supportsCSharpExternalType(ResolvedModel model, ValueType type) {
        return type.getKind() == ValueType.Kind.EXTERNAL
                && ("java.util.UUID".equals(type.getExternalType())
                || model.externalAnnotations(type.getExternalType()).stream().anyMatch(annotation ->
                        annotation.getKey().equals("vmf:csharp:external-type")
                                || annotation.getKey().equals("vmf:csharp:external-value-type")));
    }

    private static boolean hasOnlyMutableImplementations(ResolvedModel model, ValueType type) {
        var implementations = model.getTypes().stream().filter(candidate -> !candidate.isInterfaceOnly()
                && assignable(model, candidate, type.getModelType())).collect(java.util.stream.Collectors.toList());
        return !implementations.isEmpty() && implementations.stream().noneMatch(Type::isImmutable);
    }

    private static boolean isImmutableType(ResolvedModel model, ValueType type) {
        return model.findType(type.getModelType()).filter(Type::isImmutable).isPresent();
    }

    private static boolean isGetterOnlyTrait(ResolvedModel model, ValueType type) {
        return model.findType(type.getModelType()).filter(Type::isGetterOnlyTrait).isPresent();
    }

    private static boolean isSupportedContainment(Property property, Profile profile) {
        if (property.isCollection()) {
            if (!property.isOrdered()) return profile == Profile.CSHARP && property.isUnique()
                    && (property.getEntryKey().isEmpty() || property.getOppositeName().isEmpty());
            // A child has one container, so every ordered containment list holds each child once.
            return true;
        }
        return isSupportedSingularBound(property, profile);
    }

    private static boolean isSupportedSingularBound(Property property, Profile profile) {
        return property.getUpperBound() == 1 || profile == Profile.CSHARP && property.getUpperBound() == 0;
    }

    private static boolean isBoundKeyedEntry(ResolvedModel model, Type owner, Property property, ValueType key) {
        var element = property.getValueType();
        if (element.getKind() != ValueType.Kind.PARAMETER || !property.isOrdered() || !property.getOppositeName().isEmpty()) {
            return false;
        }
        boolean namedModelKey = key.getKind() == ValueType.Kind.MODEL && key.getArguments().isEmpty()
                && model.findType(key.getModelType()).filter(target -> target.getTypeParameters().isEmpty()).isPresent();
        if (!key.isPortableScalar() && !namedModelKey) return false;
        return owner.getTypeParameters().stream().anyMatch(parameter -> {
            var bound = parameter.getBound();
            return parameter.getName().equals(element.getParameterName()) && parameter.getBounds().size() == 1
                    && bound.getKind() == ValueType.Kind.MODEL && bound.getArguments().isEmpty()
                    && model.findType(bound.getModelType()).filter(target -> target.isInterfaceOnly()
                            || !target.isImmutable() && target.getTypeParameters().isEmpty()).isPresent();
        });
    }

    private static boolean containsTypeParameter(ValueType type) {
        return type.getKind() == ValueType.Kind.PARAMETER
                || type.getArguments().stream().anyMatch(PortableCapabilities::containsTypeParameter);
    }

    private static boolean isSupportedReference(Property property, Profile profile) {
        if (property.getValueType().getModelType() == null) return false;
        if (!property.isCollection()) return isSupportedSingularBound(property, profile);
        boolean opposite = !property.getOppositeName().isEmpty();
        // TypeScript reference lists allow repeats unless an opposite keeps each member once.
        if (property.isOrdered()) return profile == Profile.CSHARP || !property.isUnique() || opposite;
        return property.isUnique() && (profile == Profile.CSHARP
                || !opposite && property.getLowerBound() == 0 && property.getUpperBound() == -1);
    }

    private static void checkOpposite(ResolvedModel model, Type owner, Property property,
                                      Property.Kind oppositeKind, Profile profile, String name,
                                      List<String> diagnostics) {
        if (profile == Profile.CSHARP) {
            if (!hasSupportedOpposite(model, owner, property, oppositeKind)) {
                diagnostics.add(name + " needs a type-compatible reciprocal "
                        + oppositeKind.name().toLowerCase(Locale.ROOT) + " property on a local model type");
            }
            return;
        }
        var target = model.findType(property.getValueType().getModelType());
        var opposite = target.flatMap(type -> findProperty(type, property.getOppositeName()));
        if (opposite.isPresent() && oppositeKind == Property.Kind.CONTAINER
                && isAlternateOwningField(owner, property, target.get(), opposite.get())) {
            return;
        }
        if (opposite.isEmpty() || !matchesTypeScriptOpposite(model, owner, property, opposite.get(), oppositeKind)) {
            diagnostics.add(name + " needs a matching reciprocal " + oppositeKind.name().toLowerCase(Locale.ROOT)
                    + " property on a local model type");
        }
    }

    /** Properties that several supertypes provide must agree, because the class declares them once. */
    /**
     * A TypeScript immutable class extends its first immutable supertype and implements the other immutable
     * supertypes and getter-only traits. Its values are scalars, ordered scalar lists, and immutable values or
     * ordered lists of them.
     */
    private static void checkTypeScriptImmutable(ResolvedModel model, Type type, List<String> diagnostics) {
        String name = type.getQualifiedName();
        for (String parentName : type.getSuperTypes()) {
            var parent = model.findType(parentName);
            if (parent.isEmpty() || parent.get().isGetterOnlyTrait()) continue;
            if (parent.get().isImmutable() && !parent.get().isInterfaceOnly()) continue;
            diagnostics.add(name + " can extend immutable types and getter-only traits, found "
                    + parent.get().getQualifiedName());
        }
        if (!type.getTypeParameters().isEmpty()) {
            diagnostics.add(name + " needs a nongeneric immutable type");
        }
        for (var property : type.getEffectiveProperties()) {
            if (property.getKind() == Property.Kind.REFERENCE && property.isCollection() && !property.isOrdered()) {
                diagnostics.add(name + "." + property.getName() + " needs an ordered list on an immutable type");
            }
        }
    }

    private static void checkTypeScriptComposition(ResolvedModel model, Type type, List<String> diagnostics) {
        var declarations = new java.util.LinkedHashMap<String, List<?>>();
        var conflicts = new java.util.TreeSet<String>();
        for (var application : type.getSuperTypeApplications()) {
            var parentType = model.findType(application.getModelType());
            if (parentType.isEmpty()) continue;
            for (var property : parentType.get().getEffectiveProperties()) {
                // The declaration as this type inherits it, with the supertype's arguments applied.
                List<?> declaration = java.util.Arrays.asList(property.getKind(), property.isCollection(), property.isOrdered(),
                        property.isUnique(), property.getWireName(), property.getOppositeName(),
                        PortableInheritance.boundTypeKey(parentType.get(), application, property.getValueType()));
                var previous = declarations.putIfAbsent(property.getName(), declaration);
                // A property the type declares again is checked against each supertype in checkOverrides.
                boolean redeclared = type.getProperties().stream()
                        .anyMatch(own -> own.getName().equals(property.getName()));
                if (previous != null && !redeclared && !previous.equals(declaration)) {
                    conflicts.add(property.getName());
                }
            }
        }
        for (String conflict : conflicts) {
            diagnostics.add(type.getQualifiedName() + "." + conflict
                    + " inherits conflicting declarations from its model supertypes");
        }
    }

    /** The opposite names this property back (or is a shared container) and accepts the owner type. */
    private static boolean matchesTypeScriptOpposite(ResolvedModel model, Type owner, Property property,
                                                     Property opposite, Property.Kind oppositeKind) {
        if (opposite.getKind() != oppositeKind) return false;
        boolean named = property.getName().equals(opposite.getOppositeName())
                || oppositeKind == Property.Kind.CONTAINER && opposite.isSharedContainer();
        String accepted = opposite.getValueType().getModelType();
        // An interface-only owner can declare the end for subtypes that the opposite accepts.
        return named && (assignable(model, owner, accepted) || owner.isInterfaceOnly()
                && model.getTypes().stream().filter(candidate -> !candidate.isInterfaceOnly()
                        && assignable(model, candidate, owner.getName()))
                        .allMatch(candidate -> assignable(model, candidate, accepted)));
    }

    private static boolean hasSupportedOpposite(ResolvedModel model, Type owner, Property property,
                                                 Property.Kind inverseKind) {
        var target = model.findType(property.getValueType().getModelType());
        if (target.isEmpty()) return false;
        Type targetType = target.get();
        if (inverseKind == Property.Kind.CONTAINER && findProperty(targetType, property.getOppositeName())
                .filter(inverse -> isAlternateOwningField(owner, property, targetType, inverse)).isPresent()) {
            return true;
        }
        boolean getterOnlyChild = inverseKind == Property.Kind.CONTAINER && !owner.isGetterOnlyTrait()
                && targetType.isGetterOnlyTrait() && supportsOwningGetterRole(model, property);
        if (!getterOnlyChild && (hasCompanion(model, owner.getName()) || hasCompanion(model, targetType.getName()))) {
            boolean reciprocal = inverseKind == Property.Kind.REFERENCE;
            if (reciprocal && !supportsProjectedReciprocalEnd(owner, property)) return false;
            return property.getValueType().getKind() == ValueType.Kind.MODEL
                    && targetType.getProperties().stream().anyMatch(inverse ->
                            inverse.getName().equals(property.getOppositeName())
                                    && inverse.getValueType().getKind() == ValueType.Kind.MODEL
                                    && (!reciprocal || supportsProjectedReciprocalEnd(targetType, inverse))
                                    && matchesOpposite(owner, property, inverse, inverseKind));
        }
        if (targetType.isGetterOnlyTrait() && !getterOnlyChild) {
            return inverseKind == Property.Kind.REFERENCE && supportsGetterRoleReciprocal(model, owner, property, targetType);
        }
        if (targetType.isImmutable() || !targetType.getTypeParameters().isEmpty()
                || !property.getValueType().getArguments().isEmpty()) return false;

        // An open end can access only members declared by its upper bound.
        var access = findProperty(targetType, property.getOppositeName());
        if (access.isEmpty() || !isReciprocalBack(property, access.get(), inverseKind)
                || access.get().getValueType().getKind() != ValueType.Kind.MODEL) return false;
        for (Type actualOwner : model.getTypes()) {
            if (actualOwner.isInterfaceOnly() || !assignable(model, actualOwner, owner.getName())) continue;
            var actualEnd = findProperty(actualOwner, property.getName()).orElse(null);
            if (actualEnd == null) return false;
            String accepted = actualEnd.getValueType().getModelType();
            for (Type actualTarget : model.getTypes()) {
                if (actualTarget.isInterfaceOnly() || !assignable(model, actualTarget, accepted)) continue;
                var inverse = findProperty(actualTarget, actualEnd.getOppositeName());
                if (inverse.isEmpty() || !isReciprocalBack(actualEnd, inverse.get(), inverseKind)
                        || !assignable(model, actualOwner, inverse.get().getValueType().getModelType())) return false;
            }
        }
        return true;
    }

    private static boolean supportsGetterRoleReciprocal(ResolvedModel model, Type owner, Property collection, Type role) {
        if (owner.isInterfaceOnly() || !owner.getSuperTypes().isEmpty() || !role.getSuperTypes().isEmpty()
                || !collection.isCollection() || !declaresNamedEnd(owner, collection, role)) {
            return false;
        }
        var inverse = findProperty(role, collection.getOppositeName()).orElse(null);
        if (inverse == null || inverse.isCollection() || !inverse.isGetterOnly()
                || !declaresNamedEnd(role, inverse, owner)
                || !matchesOpposite(owner, collection, inverse, Property.Kind.REFERENCE)) return false;
        var implementations = model.getTypes().stream().filter(type -> !type.isInterfaceOnly()
                && assignable(model, type, role.getName())).collect(java.util.stream.Collectors.toList());
        return !implementations.isEmpty() && implementations.stream().allMatch(implementation -> {
            // The named mutable role supplies the single existing reciprocal field to each implementation.
            for (Type base = implementation; base != null; base = firstModelParent(model, base)) {
                if (base == role) return true;
                if (base.isImmutable() || !base.getTypeParameters().isEmpty() || base.getSuperTypes().size() != 1
                        || base.getProperties().stream().anyMatch(property -> property.getName().equals(inverse.getName()))) {
                    return false;
                }
            }
            return false;
        });
    }

    private static boolean supportsProjectedReciprocalEnd(Type owner, Property end) {
        return !owner.isImmutable() && !owner.isGetterOnlyTrait()
                && owner.getTypeParameters().isEmpty() && end.getValueType().getArguments().isEmpty()
                && (!owner.isInterfaceOnly() || !end.isGetterOnly());
    }

    static boolean usesAlternateOwningField(ResolvedModel model, Property container) {
        if (container.getKind() != Property.Kind.CONTAINER || container.isGetterOnly()) return false;
        var owner = model.findType(container.getValueType().getModelType()).orElse(null);
        var target = model.findType(container.getDeclaringType()).orElse(null);
        return owner != null && target != null && owner.getProperties().stream()
                .anyMatch(property -> isAlternateOwningField(owner, property, target, container));
    }

    private static boolean isAlternateOwningField(Type owner, Property property, Type target, Property inverse) {
        if (owner.isInterfaceOnly() || target.isInterfaceOnly() || owner.isImmutable() || target.isImmutable()
                || property.getKind() != Property.Kind.CONTAINMENT || inverse.getKind() != Property.Kind.CONTAINER
                || inverse.isGetterOnly() || !property.getOppositeName().equals(inverse.getName())
                || property.getName().equals(inverse.getOppositeName())
                || !declaresNamedEnd(owner, property, target) || !declaresNamedEnd(target, inverse, owner)) {
            return false;
        }
        return findProperty(owner, inverse.getOppositeName()).filter(canonical ->
                canonical.getKind() == Property.Kind.CONTAINMENT && canonical.getOppositeName().equals(inverse.getName())
                        && declaresNamedEnd(owner, canonical, target)).isPresent();
    }

    private static boolean declaresNamedEnd(Type declaring, Property property, Type target) {
        var value = property.getValueType();
        return declaring.getName().equals(property.getDeclaringType()) && value.getKind() == ValueType.Kind.MODEL
                && value.getArguments().isEmpty() && target.getName().equals(value.getModelType());
    }

    private static boolean hasCompanion(ResolvedModel model, String endpoint) {
        return model.getTypes().stream().flatMap(type -> type.getSuperTypes().stream().skip(1))
                .map(model::findType).flatMap(java.util.Optional::stream)
                .anyMatch(secondary -> assignable(model, secondary, endpoint));
    }

    private static java.util.Optional<Property> findProperty(Type owner, String name) {
        return owner.getEffectiveProperties().stream().filter(p -> p.getName().equals(name)).findFirst();
    }

    private static boolean isReciprocalBack(Property end, Property inverse, Property.Kind kind) {
        return inverse.getKind() == kind && (end.getName().equals(inverse.getOppositeName())
                || inverse.isSharedContainer());
    }

    private static boolean assignable(ResolvedModel model, Type type, String expected) {
        return expected != null && (type.getName().equals(expected) || model.isSubtypeOf(type, expected));
    }

    static boolean participatesInInheritance(ResolvedModel model, Type type) {
        return !type.getTypeParameters().isEmpty() || !type.getSuperTypes().isEmpty()
                || model.getTypes().stream().anyMatch(other -> other.getSuperTypes().contains(type.getName()));
    }

    private static boolean supportsExecutableScalarDefault(ResolvedModel model, Type owner) {
        if (owner.isInterfaceOnly()) {
            if (!owner.isGetterOnlyTrait() || !owner.getSuperTypes().isEmpty() || !owner.getTypeParameters().isEmpty()
                    || !owner.getEffectiveMethods().isEmpty() || owner.getConstructionHook().isPresent()
                    || owner.getProperties().stream().filter(CSharpCapabilities::bindsExecutableDefault)
                        .anyMatch(property -> !property.getValueType().isPortableScalar())) return false;
            var implementations = model.getTypes().stream().filter(type -> model.isSubtypeOf(type, owner.getName()))
                    .collect(java.util.stream.Collectors.toList());
            return !implementations.isEmpty() && implementations.stream().allMatch(type -> type.isImmutable()
                    && !type.isInterfaceOnly() && type.getTypeParameters().isEmpty()
                    && type.getSuperTypes().equals(List.of(owner.getName()))
                    && type.getEffectiveProperties().stream().allMatch(property -> property.getKind() == Property.Kind.SCALAR
                        && !property.isCollection() && property.getValueType().isPortableScalar()));
        }
        // Getter-only traits contribute signatures, so the immutable builder owns their effective defaults.
        if (owner.isImmutable() && !owner.getSuperTypes().isEmpty() && owner.getTypeParameters().isEmpty()
                && owner.getEffectiveMethods().isEmpty() && owner.getConstructionHook().isEmpty()
                && owner.getSuperTypes().stream().allMatch(name -> model.findType(name)
                        .filter(Type::isGetterOnlyTrait).isPresent())) return true;
        Type root = primaryRoot(model, owner);
        if (!owner.isImmutable() && (supportsReferenceDefaultHierarchy(model, root)
                || PortableReferenceDefaultComposition.supports(model, owner))) return true;
        String family = root.getName();
        return !hasCompanion(model, family) && model.getTypes().stream()
                .filter(type -> assignable(model, type, family))
                .allMatch(type -> type.isImmutable() == owner.isImmutable() && type.getSuperTypes().size() <= 1
                        && type.getTypeParameters().isEmpty());
    }

    private static boolean supportsReferenceSetDefault(ResolvedModel model, Type owner, Property property) {
        if (owner.isInterfaceOnly()
                || model.findType(property.getValueType().getModelType())
                    .filter(target -> !target.isInterfaceOnly() && target.getTypeParameters().isEmpty()).isEmpty()) {
            return false;
        }
        if (!participatesInInheritance(model, owner) && owner.getConstructionHook().isEmpty()) return true;
        Type root = primaryRoot(model, owner);
        return property.getDeclaringType().equals(root.getName()) && supportsReferenceDefaultHierarchy(model, root)
                || PortableReferenceDefaultComposition.supports(model, owner);
    }

    private static boolean supportsManagedDefaultWithReferences(ResolvedModel model, Type owner, Property property) {
        if (!supportsReferenceDefaultHierarchy(model, primaryRoot(model, owner))
                && !PortableReferenceDefaultComposition.supports(model, owner)) return false;
        return model.getTypes().stream().noneMatch(type -> type != owner
                && (assignable(model, type, owner.getName()) || assignable(model, owner, type.getName()))
                && type.getProperties().stream().anyMatch(member -> member.getName().equals(property.getName())));
    }

    private static Type primaryRoot(ResolvedModel model, Type type) {
        Type parent = firstModelParent(model, type);
        return parent == null ? type : primaryRoot(model, parent);
    }

    private static boolean supportsReferenceDefaultHierarchy(ResolvedModel model, Type root) {
        String family = root.getName();
        var inheritedFields = root.getProperties().stream().filter(CSharpCapabilities::hasReferenceSetDefault)
                .map(Property::getName).collect(java.util.stream.Collectors.toSet());
        return !inheritedFields.isEmpty() && !hasCompanion(model, family)
                && model.getTypes().stream().filter(type -> assignable(model, type, family)).allMatch(type ->
                    !type.isImmutable() && !type.isInterfaceOnly() && type.getTypeParameters().isEmpty()
                            && supportsReferenceDefaultSecondaryBases(model, type)
                            && (type == root || type.getProperties().stream().noneMatch(property ->
                                inheritedFields.contains(property.getName()) || CSharpCapabilities.hasReferenceSetDefault(property))));
    }

    private static boolean supportsReferenceDefaultSecondaryBases(ResolvedModel model, Type type) {
        if (type.getSuperTypes().size() < 2) return true;
        if (type.getConstructionHook().isPresent()) return false;

        // These roles contribute ordinary scalar storage to the primary constructor, without providers of their own.
        var usedNames = new HashSet<String>();
        firstModelParent(model, type).getEffectiveProperties().forEach(property -> usedNames.add(property.getName()));
        type.getProperties().forEach(property -> usedNames.add(property.getName()));
        return type.getSuperTypes().stream().skip(1).allMatch(name -> model.findType(name).filter(role ->
                !role.isImmutable() && !role.isInterfaceOnly() && role.getTypeParameters().isEmpty()
                        && role.getSuperTypes().isEmpty() && role.getEffectiveMethods().isEmpty()
                        && role.getConstructionHook().isEmpty() && role.getProperties().stream().allMatch(property ->
                            property.getKind() == Property.Kind.SCALAR && !property.isCollection()
                                    && (!property.hasDeclaredDefault() || property.getScalarDefault().isPresent())
                                    && !CSharpCapabilities.bindsExecutableDefault(property)
                                    && usedNames.add(property.getName()))).isPresent());
    }

    private static boolean supportsLiteralCollectionDefault(ResolvedModel model, Type owner, Property property) {
        if (owner.isInterfaceOnly() || !owner.getTypeParameters().isEmpty()) return false;
        if (model.getTypes().stream().anyMatch(type -> type != owner && assignable(model, type, owner.getName()))) return false;
        boolean allowsStandaloneHook = !owner.isImmutable() && owner.getSuperTypes().isEmpty();
        if (model.getTypes().stream().anyMatch(type -> assignable(model, owner, type.getName())
                && (type.getConstructionHook().isPresent() && !allowsStandaloneHook
                    || type != owner && findProperty(type, property.getName()).isPresent()))) {
            return false;
        }
        return encodesLiteralCollectionDefault(property);
    }

    private static boolean encodesLiteralCollectionDefault(Property property) {
        var literal = property.getCollectionDefault().orElseThrow();
        return java.util.stream.Stream.concat(literal.getKeys().stream(), literal.getValues().stream())
                .noneMatch(value -> value.getValue() instanceof String
                        && !StandardCharsets.UTF_8.newEncoder().canEncode((String) value.getValue()));
    }

    private static boolean matchesOpposite(Type owner, Property property, Property opposite,
                                           Property.Kind oppositeKind) {
        return opposite.getKind() == oppositeKind
                && owner.getName().equals(opposite.getValueType().getModelType())
                && property.getName().equals(opposite.getOppositeName());
    }


}
