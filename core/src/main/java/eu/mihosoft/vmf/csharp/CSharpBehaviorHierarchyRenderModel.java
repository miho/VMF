package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Preserves CLR virtual slots and the selected VMF hook across a primary model hierarchy. */
final class CSharpBehaviorHierarchyRenderModel {
    private static final List<String> RESERVED = List.of("Behavior", "BehaviorOverride", "ReplaceBehavior",
            "DefaultBehavior", "CreateDefaultBehavior", "__vmfApplyDeclaredDefaults", "__vmfRunBehaviorHook",
            "__vmfAcceptsBehavior", "__vmfSetBehaviorOverride", "__vmfBehaviorOverride",
            "__vmfDefaultBehavior", "__vmfBehaviorLock", "__vmfGetDefaultBehavior");

    private final CSharpRenderModel model;
    private final CSharpBehaviorRenderModel signatures;
    private final Map<String, Family> families = new HashMap<>();
    private final Map<Type, Set<String>> virtualRoleDispatch = new HashMap<>();
    private final Map<Type, Map<String, String>> namesByDispatchedSlot = new HashMap<>();
    private final Set<Type> reusableCompositions = new HashSet<>();

    CSharpBehaviorHierarchyRenderModel(CSharpRenderModel model, CSharpBehaviorRenderModel signatures,
                                      List<Type> types) {
        this.model = model;
        this.signatures = signatures;
        for (Type composition : types) {
            directBehaviorRoles(composition).stream().skip(1).forEach(this::collectReusableCompositions);
        }
        for (Type composition : types) {
            var roles = directBehaviorRoles(composition);
            if (roles.isEmpty()) continue;
            Type primary = roles.get(0);
            Set<String> secondaryNames = secondaryMethodNames(roles);
            primary.getEffectiveMethods().stream().filter(method -> secondaryNames.contains(method.getName()))
                    .forEach(method -> {
                        String slot = signatures.slot(primary, method);
                        Type owner = bodyOwner(primary, method, slot);
                        virtualRoleDispatch.computeIfAbsent(owner, unused -> new HashSet<>()).add(slot);
                    });
        }
        var slotsByRoot = new HashMap<Type, Set<String>>();
        virtualRoleDispatch.forEach((owner, slots) ->
                slotsByRoot.computeIfAbsent(root(owner), unused -> new HashSet<>()).addAll(slots));
        slotsByRoot.forEach((owner, slots) -> {
            // A different protected overload in a subclass must not hide the forwarded virtual slot.
            var names = new HashMap<String, String>();
            var byMethod = slots.stream().sorted().collect(Collectors.groupingBy(slot -> slot.substring(0, slot.indexOf('('))));
            byMethod.forEach((name, overloads) -> {
                for (int index = 0; index < overloads.size(); index++) {
                    String suffix = overloads.size() == 1 ? "" : "_" + (index + 1);
                    names.put(overloads.get(index), "__vmfDispatch" + name + suffix);
                }
            });
            namesByDispatchedSlot.put(owner, Map.copyOf(names));
        });
        var grouped = types.stream().filter(type -> !type.isImmutable())
                .collect(Collectors.groupingBy(type -> root(type).getName()));
        grouped.forEach((root, family) -> {
            boolean participatingRole = family.stream().anyMatch(type -> model.isCompanionType(type)
                    && (CSharpCapabilities.isUnboundBehaviorTrait(type) || model.isBoundBehaviorRole(type)));
            if (family.size() == 1 && !participatingRole) return;
            if (family.stream().anyMatch(type -> type.getConstructionHook().isPresent()
                    || !type.getEffectiveMethods().isEmpty())) {
                families.put(root, new Family(family.stream().anyMatch(type -> type.getConstructionHook().isPresent())));
            }
        });
    }

    boolean includes(Type type) {
        return !type.isImmutable() && families.containsKey(root(type).getName());
    }

    boolean defersDefaults(Type type) {
        return includes(type) && families.get(root(type).getName()).defersDefaults;
    }

    boolean isReusableComposition(Type type) {
        return reusableCompositions.contains(type);
    }

    private void collectReusableCompositions(Type type) {
        if (!model.hasBoundBehaviorBranches(type) || !reusableCompositions.add(type)) return;
        type.getSuperTypes().stream().map(model::findType).forEach(this::collectReusableCompositions);
    }

    Map<String, Object> prepare(Type type, Set<String> members, boolean hasSubtypes) {
        for (String member : RESERVED) {
            if (!members.add(member)) throw conflict(type, member);
        }
        boolean reusable = reusableRole(type);
        if (reusable) {
            for (String member : List.of("__vmfBehaviorState", "__vmfBehaviorStates")) {
                if (!members.add(member)) throw conflict(type, member);
            }
        }
        Type parent = parent(type);
        boolean hasParent = parent != null;
        var roleSlots = secondarySlots(type);
        var companionSlots = companionSlots(type);
        var directRoles = directBehaviorRoles(type);
        var declarations = roleMethodsBySlot(directRoles);
        var secondaryNames = secondaryMethodNames(directRoles);
        boolean hasRepeatedRoles = hasRepeatedRole(type, new HashSet<>());
        String secondaryBehaviors = secondaryRoles(type).stream().map(role -> ", " + model.typeName(role) + ".Behavior")
                .collect(Collectors.joining());

        var hooks = type.getConstructionHook();
        var ownHook = hooks.filter(hook -> hook.getDeclaringType().equals(type.getName()));
        String localHook = ownHook
                .map(hook -> CSharpRenderModel.identifier(hook.getName(), type.getQualifiedName())).orElse("");
        String hookBinding = ownHook.map(hook -> hook.getJavaBinding()).orElse("");
        String hookInvocation = hooks.map(hook -> {
            Type declaringType = model.findType(hook.getDeclaringType());
            for (Type ancestor = type; ancestor != null; ancestor = parent(ancestor)) {
                if (declaringType == ancestor) return "__vmfRunBehaviorHook()";
            }
            return roleFactory(declaringType, hook.getJavaBinding()) + "."
                    + CSharpRenderModel.identifier(hook.getName(), type.getQualifiedName()) + "(this)";
        }).orElse("");
        Set<String> inheritedSlots = parent == null ? Set.of()
                : parent.getEffectiveMethods().stream().map(method -> signatures.slot(parent, method)).collect(Collectors.toSet());
        var localMethods = new ArrayList<Map<String, Object>>();
        var localSlots = new ArrayList<Map<String, Object>>();
        var defaultSlots = new LinkedHashMap<String, List<Map<String, Object>>>();
        boolean namedProviders = model.usesNamedBehaviorProviders(type);
        if (!localHook.isEmpty()) defaultSlots.put(hookBinding, new ArrayList<>());
        var behaviorBridges = new ArrayList<Map<String, String>>();
        var resultBridges = new ArrayList<Map<String, String>>();
        var names = new HashSet<String>();
        var dispatchNames = new HashSet<String>();
        var unique = new HashSet<String>();
        for (var method : type.getEffectiveMethods()) {
            String name = CSharpCallableSignatures.methodName(type, method);
            String slot = signatures.slot(type, method);
            boolean objectOverride = CSharpCapabilities.isObjectOverride(type, method);
            if (name.equals(localHook) || (!objectOverride && names.add(name) && !members.add(name)) || !unique.add(slot)) {
                throw conflict(type, slot);
            }
            var rendered = new HashMap<String, Object>(signatures.prepareMethod(type, method, name, behaviorCallerType(type)));
            var roleSlot = roleSlots.get(slot);
            if (roleSlot != null) checkResult(type, method, roleSlot.getKey(), roleSlot.getValue());
            for (var companion : companionSlots.getOrDefault(slot, List.of())) {
                Type source = companion.getKey();
                CallableMethod inherited = companion.getValue();
                if (sameResult(type, method, source, inherited)) continue;
                checkResult(type, method, source, inherited);
                var bridge = new HashMap<>(signatures.prepareMethod(source, inherited, name));
                bridge.put("companion", model.modelUseName(source));
                resultBridges.add(Map.copyOf(bridge));
            }
            var matchingRoles = declarations.getOrDefault(slot, List.of());
            boolean joined = matchingRoles.size() > 1;
            if (joined) {
                var bridgeContracts = new LinkedHashMap<Type, CallableMethod>();
                for (var declaration : matchingRoles) {
                    Type source = declaration.getKey();
                    CallableMethod candidate = declaration.getValue();
                    if (!method.getSourceSignature().equals(candidate.getSourceSignature())) {
                        throw new IllegalArgumentException("Cannot generate C# behavior for " + type.getQualifiedName()
                                + "." + slot + ": distinct Java callables map to the same CLR signature");
                    }
                    checkResult(type, method, source, candidate);
                    if (hasRepeatedRoles) {
                        collectReplacementContracts(source, candidate, bridgeContracts);
                    } else {
                        var contract = replacementContract(source, candidate);
                        bridgeContracts.put(contract.getKey(), contract.getValue());
                    }
                }
                bridgeContracts.forEach((source, candidate) -> behaviorBridges.add(behaviorBridge(type, source, candidate, name)));
                localSlots.add(Map.copyOf(rendered));
            }

            boolean overrides = inheritedSlots.contains(slot);
            boolean separatesDispatch = usesVirtualRoleDispatch(type, slot);
            boolean joinsOverloads = overrides && secondaryNames.contains(method.getName());
            if (separatesDispatch) {
                String dispatchName = namesByDispatchedSlot.get(root(type)).get(slot);
                if (dispatchNames.add(dispatchName) && !members.add(dispatchName)) throw conflict(type, dispatchName);
                rendered.put("bodyName", dispatchName);
                rendered.put("access", "protected");
            } else {
                rendered.put("bodyName", name);
                rendered.put("access", "public");
            }
            rendered.put("publicForward", separatesDispatch && (!overrides || joinsOverloads));
            rendered.put("forwardModifier", joinsOverloads ? "new " : "virtual ");
            boolean emitBody = !overrides || type.getName().equals(method.getDeclaringType());
            rendered.put("emitBody", emitBody);
            if (!emitBody) {
                if (joinsOverloads) localMethods.add(Map.copyOf(rendered));
                continue;
            }
            rendered.put("replacementReceiver", roleSlot == null || joined ? "replacement"
                    : "((" + model.typeName(replacementContract(roleSlot.getKey(), roleSlot.getValue()).getKey())
                            + ".Behavior)replacement)");
            boolean defaultBinding = !method.getJavaBinding().isBlank();
            Type declaringType = model.findType(method.getDeclaringType());
            boolean usesRoleFactory = reusableRole(declaringType);
            rendered.put("isVoid", method.getReturnType().getKind() == CallableType.Kind.VOID);
            rendered.put("hasDefaultBinding", defaultBinding);
            String binding = method.getJavaBinding();
            rendered.put("defaultFactory", !defaultBinding ? "" : usesRoleFactory ? roleFactory(declaringType, binding)
                    : model.behaviorProviderMember(type, binding, "__vmfGetDefaultBehavior") + "()");
            rendered.put("modifier", overrides || objectOverride ? "override " : hasSubtypes ? "virtual " : "");
            var declaration = Map.copyOf(rendered);
            localMethods.add(declaration);
            if (!overrides && roleSlot == null) localSlots.add(declaration);
            if (defaultBinding && (!usesRoleFactory || declaringType == type)) {
                String provider = namedProviders || defaultSlots.isEmpty() ? binding : defaultSlots.keySet().iterator().next();
                defaultSlots.computeIfAbsent(provider, unused -> new ArrayList<>()).add(declaration);
            }
        }
        var providers = new ArrayList<Map<String, Object>>();
        defaultSlots.forEach((binding, slots) -> providers.add(prepareProvider(type, members, reusable, binding, slots,
                hookBinding.equals(binding) ? localHook : "")));
        String hookAccess = model.behaviorProviderMember(type, hookBinding, "__vmfGetDefaultBehavior")
                + (reusable ? "(this)" : "()");
        return Map.ofEntries(
                Map.entry("hasParent", hasParent),
                Map.entry("baseType", hasParent ? className(type, type.getSuperTypeApplications().get(0)) : ""),
                Map.entry("rootType", rootClassName(type)),
                Map.entry("localSlots", List.copyOf(localSlots)),
                Map.entry("localMethods", List.copyOf(localMethods)),
                Map.entry("behaviorBridges", List.copyOf(behaviorBridges)),
                Map.entry("resultBridges", List.copyOf(resultBridges)),
                Map.entry("providers", List.copyOf(providers)),
                Map.entry("hasFactory", !providers.isEmpty()),
                Map.entry("ownHook", localHook),
                Map.entry("ownHookAccess", hookAccess),
                Map.entry("hasOwnHook", !localHook.isEmpty()),
                Map.entry("hasHook", hooks.isPresent()),
                Map.entry("hookInvocation", hookInvocation),
                Map.entry("secondaryBehaviors", secondaryBehaviors),
                Map.entry("reusableRole", reusable),
                Map.entry("roleContract", reusable ? model.modelUseName(type) : ""),
                Map.entry("hookModifier", (hasSubtypes ? "protected " : "private ")
                        + (hasAncestorHook(type) ? "new " : "")),
                Map.entry("acceptModifier", hasParent ? "override " : "virtual "));
    }

    private Map<String, List<Map.Entry<Type, CallableMethod>>> companionSlots(Type type) {
        var companions = new LinkedHashSet<Type>();
        if (model.isCompanionType(type)) {
            collectCompanions(type, companions);
        } else {
            type.getSuperTypes().stream().skip(1).map(model::findType)
                    .forEach(role -> collectCompanions(role, companions));
        }
        var slots = new HashMap<String, List<Map.Entry<Type, CallableMethod>>>();
        for (Type companion : companions) {
            for (var method : model.companionCallables(companion)) {
                slots.computeIfAbsent(signatures.slot(companion, method), unused -> new ArrayList<>())
                        .add(Map.entry(companion, method));
            }
        }
        return slots;
    }

    private void collectCompanions(Type type, Set<Type> companions) {
        if (!model.isCompanionType(type) || !companions.add(type)) return;
        type.getSuperTypes().stream().map(model::findType).forEach(parent -> collectCompanions(parent, companions));
    }

    private boolean hasRepeatedRole(Type type, Set<Type> seen) {
        return !seen.add(type) || directBehaviorRoles(type).stream().anyMatch(role -> hasRepeatedRole(role, seen));
    }

    private void collectReplacementContracts(Type type, CallableMethod method, Map<Type, CallableMethod> result) {
        // Competing CLR default implementations also require an explicit bridge for their common base.
        var contract = replacementContract(type, method);
        Type source = contract.getKey();
        if (result.putIfAbsent(source, contract.getValue()) != null) return;
        String slot = signatures.slot(source, contract.getValue());
        for (Type branch : directBehaviorRoles(source)) {
            methodForSlot(branch, slot).ifPresent(inherited -> collectReplacementContracts(branch, inherited, result));
        }
    }

    private Type bodyOwner(Type type, CallableMethod method, String slot) {
        if (type.getName().equals(method.getDeclaringType())) return type;
        Type superclass = parent(type);
        return superclass == null ? type : methodForSlot(superclass, slot)
                .map(inherited -> bodyOwner(superclass, inherited, slot)).orElse(type);
    }

    private Optional<CallableMethod> methodForSlot(Type type, String slot) {
        return type.getEffectiveMethods().stream().filter(method -> signatures.slot(type, method).equals(slot)).findFirst();
    }

    private Map.Entry<Type, CallableMethod> replacementContract(Type type, CallableMethod method) {
        String slot = signatures.slot(type, method);
        if (directBehaviorRoles(type).stream().filter(role -> methodForSlot(role, slot).isPresent()).count() > 1) {
            return Map.entry(type, method);
        }
        Type superclass = parent(type);
        if (superclass != null) {
            var inherited = methodForSlot(superclass, slot);
            if (inherited.isPresent()) return replacementContract(superclass, inherited.get());
        }
        var secondary = secondarySlots(type).get(slot);
        return secondary == null ? Map.entry(type, method) : replacementContract(secondary.getKey(), secondary.getValue());
    }

    private Map<String, Object> prepareProvider(Type type, Set<String> members, boolean reusable, String binding,
                                                List<Map<String, Object>> slots, String hook) {
        String name = model.behaviorProviderMember(type, binding, "DefaultBehavior");
        String factory = model.behaviorProviderMember(type, binding, "CreateDefaultBehavior");
        String accessor = model.behaviorProviderMember(type, binding, "__vmfGetDefaultBehavior");
        String storage = model.behaviorProviderMember(type, binding, "__vmfDefaultBehavior");
        String lock = model.behaviorProviderMember(type, binding, "__vmfBehaviorLock");
        String state = model.behaviorProviderMember(type, binding, "__vmfBehaviorState");
        String byOwner = model.behaviorProviderMember(type, binding, "__vmfBehaviorStates");
        if (!name.equals("DefaultBehavior")) {
            for (String member : List.of(name, factory, accessor, storage, lock, state, byOwner)) {
                if (!members.add(member)) throw conflict(type, member);
            }
        }
        return Map.ofEntries(
                Map.entry("name", name), Map.entry("factory", factory), Map.entry("accessor", accessor),
                Map.entry("storage", storage), Map.entry("lock", lock),
                Map.entry("state", state), Map.entry("byOwner", byOwner),
                Map.entry("hide", hasAncestorFactory(type, name)),
                Map.entry("shared", model.sharedBehaviorAccessor(type, binding)),
                Map.entry("slots", List.copyOf(slots)), Map.entry("hook", hook),
                Map.entry("caller", reusable ? model.modelUseName(type) : instanceName(type)));
    }

    private boolean usesVirtualRoleDispatch(Type type, String slot) {
        for (Type ancestor = type; ancestor != null; ancestor = parent(ancestor)) {
            if (virtualRoleDispatch.getOrDefault(ancestor, Set.of()).contains(slot)) return true;
        }
        return false;
    }

    private List<Type> directBehaviorRoles(Type type) {
        return model.hasBoundBehaviorBranches(type) ? type.getSuperTypes().stream()
                .map(model::findType).collect(Collectors.toUnmodifiableList()) : List.of();
    }

    private static Set<String> secondaryMethodNames(List<Type> roles) {
        return roles.stream().skip(1).flatMap(role -> role.getEffectiveMethods().stream())
                .map(CallableMethod::getName).collect(Collectors.toSet());
    }

    private Map<String, List<Map.Entry<Type, CallableMethod>>> roleMethodsBySlot(List<Type> roles) {
        var declarations = new HashMap<String, List<Map.Entry<Type, CallableMethod>>>();
        for (Type role : roles) {
            for (CallableMethod method : role.getEffectiveMethods()) {
                declarations.computeIfAbsent(signatures.slot(role, method), unused -> new ArrayList<>())
                        .add(Map.entry(role, method));
            }
        }
        return declarations;
    }

    private String behaviorCallerType(Type type) {
        return reusableRole(type) ? model.modelUseName(type) : instanceName(type);
    }

    private Map<String, String> behaviorBridge(Type type, Type source, CallableMethod method, String name) {
        var rendered = new HashMap<>(signatures.prepareMethod(source, method, name, behaviorCallerType(source)));
        String arguments = rendered.get("parameterArguments");
        rendered.put("behaviorType", model.typeName(source) + ".Behavior");
        rendered.put("arguments", "(" + behaviorCallerType(type) + ")caller" + (arguments.isEmpty() ? "" : ", " + arguments));
        return Map.copyOf(rendered);
    }

    private void checkResult(Type type, CallableMethod method, Type source, CallableMethod inherited) {
        // Java flattens inherited declarations but emits duplicate signatures for local covariant redeclarations.
        if (!sameResult(type, method, source, inherited) && (type.getName().equals(method.getDeclaringType())
                || !signatures.canWidenResult(type, method.getReturnType(), source, inherited.getReturnType()))) {
            throw new IllegalArgumentException("Cannot generate C# behavior for " + type.getQualifiedName() + "."
                    + method.getName() + ": overrides a secondary callable with a different result type");
        }
    }

    private boolean sameResult(Type type, CallableMethod method, Type source, CallableMethod inherited) {
        return signatures.typeName(source, inherited.getReturnType()).equals(signatures.typeName(type, method.getReturnType()));
    }

    private boolean reusableRole(Type type) {
        return model.isCompanionType(type) && (model.isBoundBehaviorRole(type) || isReusableComposition(type));
    }

    private Set<Type> secondaryRoles(Type type) {
        var roles = new LinkedHashSet<Type>();
        var visited = new HashSet<Type>();
        type.getSuperTypes().stream().skip(1).map(model::findType)
                .forEach(parent -> collectBehaviorRoots(parent, visited, roles));
        return roles;
    }

    private void collectBehaviorRoots(Type type, Set<Type> visited, Set<Type> roles) {
        if (!visited.add(type)) return;
        if (reusableRole(type)) {
            roles.add(type);
            return;
        }
        type.getSuperTypes().stream().map(model::findType)
                .forEach(parent -> collectBehaviorRoots(parent, visited, roles));
    }

    private Map<String, Map.Entry<Type, CallableMethod>> secondarySlots(Type type) {
        var slots = new HashMap<String, Map.Entry<Type, CallableMethod>>();
        for (Type ancestor = type; ancestor != null; ancestor = parent(ancestor)) {
            for (var role : secondaryRoles(ancestor)) {
                for (var method : role.getEffectiveMethods()) {
                    slots.putIfAbsent(signatures.slot(role, method), Map.entry(role, method));
                }
            }
        }
        return slots;
    }

    private String roleFactory(Type type, String binding) {
        return model.typeName(type) + "."
                + model.behaviorProviderMember(type, binding, "__vmfGetDefaultBehavior") + "(this)";
    }

    private String instanceName(Type type) {
        return CSharpRenderModel.withTypeArguments(model.typeName(type), formalNames(type));
    }

    private List<String> formalNames(Type type) {
        return type.getTypeParameters().stream()
                .map(parameter -> CSharpRenderModel.identifier(parameter.getName(), type.getQualifiedName()))
                .collect(Collectors.toList());
    }

    private boolean hasAncestorHook(Type type) {
        for (Type ancestor = parent(type); ancestor != null; ancestor = parent(ancestor)) {
            String name = ancestor.getName();
            if (ancestor.getConstructionHook().filter(hook -> hook.getDeclaringType().equals(name)).isPresent()) return true;
        }
        return false;
    }

    private boolean hasAncestorFactory(Type type, String name) {
        for (Type ancestor = parent(type); ancestor != null; ancestor = parent(ancestor)) {
            for (String binding : CSharpDefaultBehaviorNames.declaredBindings(ancestor)) {
                if (model.behaviorProviderMember(ancestor, binding, "DefaultBehavior").equals(name)) return true;
            }
        }
        return false;
    }

    private Type parent(Type type) {
        return type.getSuperTypes().isEmpty() ? null : model.findType(type.getSuperTypes().get(0));
    }

    private Type root(Type type) {
        Type ancestor = parent(type);
        return ancestor == null ? type : root(ancestor);
    }

    private String className(Type context, ValueType value) {
        Type declaration = model.findType(value.getModelType());
        return CSharpRenderModel.withTypeArguments(model.typeName(declaration),
                model.renderGenericArguments(context, declaration, value, model::typeName));
    }

    private String rootClassName(Type type) {
        var arguments = formalNames(type);
        Type current = type;
        while (parent(current) != null) {
            var bindings = new HashMap<String, String>();
            for (int index = 0; index < arguments.size(); index++) {
                bindings.put(current.getTypeParameters().get(index).getName(), arguments.get(index));
            }
            Type ancestor = parent(current);
            arguments = model.renderGenericArguments(current, ancestor, current.getSuperTypeApplications().get(0),
                    value -> renderArgument(value, bindings));
            current = ancestor;
        }
        return CSharpRenderModel.withTypeArguments(model.typeName(current), arguments);
    }

    private String renderArgument(ValueType value, Map<String, String> bindings) {
        if (value.getKind() == ValueType.Kind.PARAMETER) {
            return bindings.getOrDefault(value.getParameterName(), model.typeName(value));
        }
        if (value.getKind() == ValueType.Kind.MODEL && !value.getArguments().isEmpty()) {
            return CSharpRenderModel.withTypeArguments(model.typeName(model.findType(value.getModelType())),
                    value.getArguments().stream().map(argument -> renderArgument(argument, bindings))
                            .collect(Collectors.toList()));
        }
        return model.typeName(value);
    }

    private static IllegalArgumentException conflict(Type owner, String member) {
        return new IllegalArgumentException("Cannot generate C# behavior for " + owner.getQualifiedName()
                + ": '" + member + "' conflicts with another C# member or signature");
    }

    private static final class Family {
        private final boolean defersDefaults;

        private Family(boolean defersDefaults) {
            this.defersDefaults = defersDefaults;
        }
    }
}
