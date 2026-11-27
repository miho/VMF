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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks the portable model features, scalar constraints, and defaults supported by the C# backend. */
public final class CSharpCapabilities {
    public static final String EXECUTABLE_DEFAULT_ANNOTATION = "vmf:csharp:bind-default";

    private CSharpCapabilities() {
    }

    public static void validate(ResolvedModel model) {
        PortableCapabilities.validate(model, "C#", PortableCapabilities.Profile.CSHARP);
    }

    public static boolean delegatesObjectToString(ResolvedModel.Type owner) {
        return owner.getEffectiveMethods().stream().anyMatch(method -> isObjectToString(owner, method));
    }

    public static boolean isObjectToString(ResolvedModel.Type owner, ResolvedModel.CallableMethod method) {
        return !owner.isInterfaceOnly() && method.getName().equals("toString")
                && !method.isStatic() && !method.hasCheckedExceptions() && method.getParameters().isEmpty()
                && method.getReturnType().getKind() == ResolvedModel.CallableType.Kind.VALUE
                && method.getReturnType().getValueType().getKind() == ResolvedModel.ValueType.Kind.STRING;
    }

    public static boolean delegatesObjectEquality(ResolvedModel.Type owner) {
        return owner.getEffectiveMethods().stream().anyMatch(CSharpCapabilities::isObjectEquals);
    }

    public static boolean isObjectEquals(ResolvedModel.CallableMethod method) {
        if (!method.getName().equals("equals") || method.getParameters().size() != 1
                || !hasPrimitiveResult(method, ResolvedModel.ValueType.Kind.BOOLEAN)) return false;
        var parameter = method.getParameters().get(0).getType();
        return parameter.getKind() == ResolvedModel.CallableType.Kind.VALUE
                && Object.class.getName().equals(parameter.getValueType().getExternalType());
    }

    public static boolean isObjectHashCode(ResolvedModel.CallableMethod method) {
        return method.getName().equals("hashCode") && method.getParameters().isEmpty()
                && hasPrimitiveResult(method, ResolvedModel.ValueType.Kind.INT32);
    }

    public static boolean isObjectOverride(ResolvedModel.Type owner, ResolvedModel.CallableMethod method) {
        return isObjectToString(owner, method) || isObjectEquals(method) || isObjectHashCode(method);
    }

    private static boolean hasPrimitiveResult(ResolvedModel.CallableMethod method, ResolvedModel.ValueType.Kind kind) {
        var result = method.getReturnType();
        return !method.isStatic() && !method.hasCheckedExceptions()
                && result.getKind() == ResolvedModel.CallableType.Kind.VALUE
                && result.getValueType().getKind() == kind && result.getValueType().isPrimitive();
    }

    public static boolean usesAlternateOwningField(ResolvedModel model, ResolvedModel.Property container) {
        return PortableCapabilities.usesAlternateOwningField(model, container);
    }

    public static boolean promotesKeyedSequence(ResolvedModel model, ResolvedModel.Type owner,
                                                ResolvedModel.Property effective) {
        return PortableInheritance.promotesKeyedSequence(model, owner, effective);
    }

    public static boolean projectsRelationshipGetter(ResolvedModel model, ResolvedModel.Type owner,
                                                     ResolvedModel.Property property) {
        return PortableCapabilities.projectsRelationshipGetter(model, owner, property);
    }

    public static boolean implementsObjectAlternativeGetter(ResolvedModel model, ResolvedModel.Type owner,
                                                             ResolvedModel.Property property) {
        return PortableCapabilities.implementsObjectAlternativeGetter(model, owner, property);
    }

    public static boolean isObjectGetter(ResolvedModel.Property property) {
        return property.getKind() == ResolvedModel.Property.Kind.SCALAR && !property.isCollection()
                && property.isGetterOnly() && Object.class.getName().equals(property.getValueType().getExternalType())
                && !property.hasDeclaredDefault() && property.getConstraints().isEmpty();
    }

    public static boolean narrowsObjectGetter(ResolvedModel.Property narrow, ResolvedModel.Property broad) {
        return isObjectGetter(broad) && narrow.getName().equals(broad.getName())
                && narrow.getKind() == ResolvedModel.Property.Kind.SCALAR && !narrow.isCollection()
                && !narrow.isGetterOnly() && narrow.getValueType().isPortableScalar() && !narrow.getValueType().isPrimitive()
                && (!narrow.hasDeclaredDefault() || narrow.getScalarDefault().map(value -> value.getValue() == null).orElse(false))
                && narrow.getLowerBound() == broad.getLowerBound()
                && narrow.getUpperBound() == broad.getUpperBound() && narrow.getWireName().equals(broad.getWireName());
    }

    public static boolean isUnboundBehaviorTrait(ResolvedModel.Type type) {
        return type.isInterfaceOnly() && !type.isImmutable() && type.getTypeParameters().isEmpty()
                && type.getEffectiveProperties().isEmpty() && type.getConstructionHook().isEmpty()
                && !type.getEffectiveMethods().isEmpty()
                && type.getEffectiveMethods().stream().allMatch(method -> method.getJavaBinding().isBlank());
    }

    public static boolean isBoundBehaviorRoot(ResolvedModel.Type type) {
        return type.getSuperTypes().isEmpty() && declaresBoundBehavior(type);
    }

    public static boolean isBoundBehaviorRole(ResolvedModel model, ResolvedModel.Type type) {
        if (isBoundBehaviorDeclaration(model, type)) return true;
        if (type.isImmutable() || type.isInterfaceOnly() || !type.getTypeParameters().isEmpty()
                || type.getSuperTypes().size() != 1) return false;
        // Concrete linear roles can inherit their provider or contribute one local binding.
        boolean inheritsProvider = type.getDeclaredMethods().isEmpty()
                && type.getConstructionHook().filter(hook -> hook.getDeclaringType().equals(type.getName())).isEmpty();
        return (inheritsProvider || !singleLocalBehaviorBinding(type).isEmpty())
                && model.findType(type.getSuperTypes().get(0))
                        .filter(base -> isBoundBehaviorRole(model, base)).isPresent();
    }

    private static boolean isBoundBehaviorDeclaration(ResolvedModel model, ResolvedModel.Type type) {
        return isBoundBehaviorRoot(type) || (declaresBoundBehavior(type)
                && PortableInheritance.hasClosedDataOnlyAncestors(model, type.getSuperTypes().stream()));
    }

    public static boolean hasDirectBoundBehaviorRoles(ResolvedModel model, ResolvedModel.Type type) {
        return !type.isInterfaceOnly() && !type.isImmutable() && type.getTypeParameters().isEmpty()
                && type.getSuperTypes().size() >= 2 && type.getSuperTypes().stream().allMatch(name ->
                        model.findType(name).filter(parent -> isBoundBehaviorRole(model, parent)).isPresent());
    }

    public static boolean hasBoundBehaviorBranches(ResolvedModel model, ResolvedModel.Type type) {
        return hasBoundBehaviorBranches(model, type, new HashSet<>());
    }

    private static boolean hasBoundBehaviorBranches(ResolvedModel model, ResolvedModel.Type type, Set<String> seen) {
        return !type.isInterfaceOnly() && !type.isImmutable() && type.getTypeParameters().isEmpty()
                && type.getSuperTypes().size() >= 2 && type.getSuperTypes().stream().allMatch(name -> !seen.add(name)
                        || model.findType(name).filter(parent -> isBoundBehaviorRole(model, parent)
                                || hasBoundBehaviorBranches(model, parent, seen)).isPresent());
    }

    public static Map<String, List<String>> sharedBehaviorBindings(ResolvedModel model, ResolvedModel.Type type) {
        if (!hasDirectBoundBehaviorRoles(model, type)) return Map.of();
        var groups = new LinkedHashMap<String, List<String>>();
        for (String name : type.getSuperTypes()) {
            String binding = singleLocalBehaviorBinding(model.findType(name).orElseThrow());
            if (!binding.isEmpty()) groups.computeIfAbsent(binding, unused -> new ArrayList<>()).add(name);
        }
        groups.values().removeIf(roles -> roles.size() < 2);
        groups.replaceAll((binding, roles) -> List.copyOf(roles));
        return Map.copyOf(groups);
    }

    private static String singleLocalBehaviorBinding(ResolvedModel.Type type) {
        var methods = type.getDeclaredMethods();
        if (methods.isEmpty()) return "";
        String binding = methods.get(0).getJavaBinding();
        if (methods.stream().anyMatch(method -> !method.getJavaBinding().equals(binding))
                || type.getConstructionHook().filter(hook -> hook.getDeclaringType().equals(type.getName())
                        && !hook.getJavaBinding().equals(binding)).isPresent()) {
            return "";
        }
        return binding;
    }

    private static boolean declaresBoundBehavior(ResolvedModel.Type type) {
        return !type.isImmutable() && type.getTypeParameters().isEmpty() && !type.getEffectiveMethods().isEmpty()
                && type.getEffectiveMethods().stream().allMatch(method -> method.getDeclaringType().equals(type.getName())
                        && !method.getJavaBinding().isBlank());
    }

    public static boolean bindsExecutableDefault(ResolvedModel.Property property) {
        return property.hasDeclaredDefault() && property.getScalarDefault().isEmpty()
                && property.getKind() == ResolvedModel.Property.Kind.SCALAR && !property.isCollection()
                && property.getAnnotationValues(EXECUTABLE_DEFAULT_ANNOTATION).equals(java.util.List.of("true"));
    }

    public static boolean bindsExecutableCollectionDefault(ResolvedModel.Property property) {
        if (!property.getFeatures().contains(ResolvedModel.Feature.DEFAULT_VALUE)
                || !property.isCollection() || !property.getValueType().isPortableScalar()
                || !property.getAnnotationValues(EXECUTABLE_DEFAULT_ANNOTATION).equals(java.util.List.of("true"))) {
            return false;
        }
        return property.getKind() == ResolvedModel.Property.Kind.MAP ? property.getKeyType().isPortableScalar()
                : property.getKind() == ResolvedModel.Property.Kind.SCALAR && !property.allowsNullElements();
    }

    public static boolean hasManagedCollectionDefault(ResolvedModel.Property property) {
        return property.getCollectionDefault().isPresent() || bindsExecutableCollectionDefault(property);
    }

    /** The mutable builder and snapshot reader independently stage the selected defaults for this declaration. */
    public static boolean stagesExecutableCollectionDefaults(ResolvedModel model, ResolvedModel.Type type) {
        return !type.isImmutable() && !type.isInterfaceOnly()
                && type.getEffectiveProperties().stream().anyMatch(CSharpCapabilities::hasManagedCollectionDefault)
                && (supportsInheritedExecutableCollectionDefaults(model, type)
                    || !PortableCapabilities.participatesInInheritance(model, type)
                        && type.getEffectiveProperties().stream().anyMatch(CSharpCapabilities::bindsExecutableCollectionDefault));
    }

    static boolean redeclaresInheritedManagedCollectionDefault(ResolvedModel model, ResolvedModel.Type type,
                                                               ResolvedModel.Property property) {
        var root = collectionDefaultRoot(model, type);
        return root != null && root != type && root.getProperties().stream()
                .anyMatch(origin -> origin.getName().equals(property.getName()) && bindsExecutableCollectionDefault(origin))
                && supportsInheritedExecutableCollectionDefaults(model, type);
    }

    static boolean supportsInheritedExecutableCollectionDefaults(ResolvedModel model, ResolvedModel.Type type) {
        var root = collectionDefaultRoot(model, type);
        if (root == null) return false;
        var defaults = new LinkedHashMap<String, ResolvedModel.Property>();
        root.getProperties().stream().filter(CSharpCapabilities::bindsExecutableCollectionDefault)
                .forEach(property -> defaults.put(property.getName(), property));
        if (defaults.isEmpty()) return false;
        String rootName = root.getName();
        var descendants = model.getTypes().stream()
                .filter(candidate -> !candidate.getName().equals(rootName) && model.isSubtypeOf(candidate, rootName))
                .collect(java.util.stream.Collectors.toList());
        return !descendants.isEmpty() && descendants.stream().allMatch(descendant ->
                !descendant.isInterfaceOnly() && !descendant.isImmutable()
                && descendant.getTypeParameters().isEmpty() && descendant.getSuperTypes().size() == 1
                && descendant.getProperties().stream().allMatch(property -> {
                    var origin = defaults.get(property.getName());
                    return origin == null ? !bindsExecutableCollectionDefault(property)
                            : PortableInheritance.sameManagedDefaultSlot(descendant, property, root, origin);
                })
                && descendant.getEffectiveProperties().stream().noneMatch(CSharpCapabilities::hasReferenceSetDefault));
    }

    private static ResolvedModel.Type collectionDefaultRoot(ResolvedModel model, ResolvedModel.Type type) {
        var root = type;
        while (!root.isInterfaceOnly() && root.getSuperTypes().size() == 1) {
            root = model.findType(root.getSuperTypes().get(0)).orElse(null);
            if (root == null) return null;
        }
        if (!root.isInterfaceOnly() || root.isImmutable() || root.isGetterOnlyTrait()
                || !root.getSuperTypes().isEmpty() || !root.getTypeParameters().isEmpty()
                || !root.getEffectiveMethods().isEmpty() || root.getConstructionHook().isPresent()) return null;
        return root;
    }

    public static boolean hasReferenceSetDefault(ResolvedModel.Property property) {
        return property.getReferenceSetDefault().isPresent() || bindsReferenceSetDefault(property);
    }

    public static boolean bindsReferenceSetDefault(ResolvedModel.Property property) {
        return property.getFeatures().contains(ResolvedModel.Feature.DEFAULT_VALUE)
                && property.getKind() == ResolvedModel.Property.Kind.REFERENCE && property.isCollection()
                && property.isUnique() && property.getOppositeName().isEmpty()
                && property.getValueType().getKind() == ResolvedModel.ValueType.Kind.MODEL
                && property.getValueType().getArguments().isEmpty()
                && property.getAnnotationValues(EXECUTABLE_DEFAULT_ANNOTATION).equals(java.util.List.of("true"));
    }
}
