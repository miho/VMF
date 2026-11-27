package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Lowers immutable behavior to typed invocation interfaces and supported Object overrides. */
final class CSharpImmutableBehaviorRenderModel {
    private final CSharpRenderModel model;
    private final CSharpBehaviorRenderModel signatures;
    private final Map<Type, Map<String, CallPlan>> callPlans = new HashMap<>();

    CSharpImmutableBehaviorRenderModel(CSharpRenderModel model, CSharpBehaviorRenderModel signatures) {
        this.model = model;
        this.signatures = signatures;
    }

    static boolean hasCalls(Type owner) {
        return owner.isImmutable() && !owner.isInterfaceOnly() && owner.getEffectiveMethods().stream()
                .anyMatch(method -> !CSharpCapabilities.isObjectOverride(owner, method));
    }

    static String callsName(String owner) {
        return "I" + owner + "Calls";
    }

    Map<String, Object> prepare(Type owner, String ownerName, Set<String> members) {
        if (!owner.isImmutable() || owner.isInterfaceOnly() || owner.getEffectiveMethods().isEmpty()) {
            return Map.of();
        }
        boolean hasCalls = hasCalls(owner);

        String calls = callsName(ownerName);
        var parents = behaviorBases(owner);
        String extendsCalls = parents.isEmpty() ? "" : parents.stream().map(model::typeName)
                .map(CSharpImmutableBehaviorRenderModel::callsName).collect(Collectors.joining(", ", " : ", ""));
        var plan = planCalls(owner);
        var resultBridges = new ArrayList<Map<String, String>>();
        var groups = owner.getEffectiveMethods().stream().collect(Collectors.groupingBy(
                CallableMethod::getJavaBinding, LinkedHashMap::new, Collectors.toList()));
        boolean separateBindings = groups.size() > 1;
        var names = separateBindings ? CSharpDefaultBehaviorNames.disambiguate(groups.keySet(),
                binding -> bindingName(binding, owner)) : Map.<String, String>of();
        var slots = new HashSet<String>();
        var bindings = new ArrayList<Map<String, Object>>();
        var allMethods = new ArrayList<Map<String, String>>();
        for (var group : groups.entrySet()) {
            String javaBinding = group.getKey();
            String suffix = separateBindings ? "_" + names.get(javaBinding) : "";
            String contract = "Behavior" + suffix;
            String factory = separateBindings ? "CreateBehavior" + suffix : "CreateDefaultBehavior";
            String getter = "__vmfGetImmutableBehavior" + suffix;
            String state = "__vmfImmutableBehavior" + suffix;
            String initialized = "__vmfImmutableBehaviorInitialized" + suffix;
            String syncLock = "__vmfImmutableBehaviorLock" + suffix;
            for (String member : List.of(contract, factory, getter, state, initialized, syncLock)) {
                if (!members.add(member)) throw conflict(owner, member);
            }

            var methods = new ArrayList<Map<String, String>>();
            for (var method : group.getValue()) {
                String name = CSharpCallableSignatures.methodName(owner, method);
                String slot = signatures.slot(owner, method);
                if (name.equals(calls) || name.equals(contract) || !slots.add(slot)) throw conflict(owner, slot);

                var declaration = new LinkedHashMap<>(signatures.prepareMethod(owner, method, name));
                if (CSharpCapabilities.isObjectOverride(owner, method)) {
                    methods.add(Map.copyOf(declaration));
                    continue;
                }
                var call = plan.get(slot);
                declaration.put("calls", call.selected.contract);
                if (call.declaresOwn) {
                    declaration.put("modifier", call.inherited.isEmpty() ? "" : "new ");
                    allMethods.add(Map.copyOf(declaration));
                }
                for (var inheritedSlot : call.inherited) {
                    String selected = call.selected.contract;
                    if (inheritedSlot.contract.equals(selected)) continue;
                    var bridge = new LinkedHashMap<>(declaration);
                    bridge.put("returnType", inheritedSlot.returnType);
                    bridge.put("calls", inheritedSlot.contract);
                    bridge.put("selected", selected);
                    resultBridges.add(Map.copyOf(bridge));
                }
                methods.add(Map.copyOf(declaration));
            }
            bindings.add(Map.of("contract", contract, "factory", factory, "getter", getter, "state", state,
                    "initialized", initialized, "syncLock", syncLock, "methods", List.copyOf(methods)));
        }
        return Map.of("hasCalls", hasCalls, "calls", calls, "extendsCalls", extendsCalls,
                "methods", List.copyOf(allMethods),
                "bindings", List.copyOf(bindings), "resultBridges", List.copyOf(resultBridges));
    }

    private List<Type> behaviorBases(Type owner) {
        // Call interfaces inherit independently of the CLR class used to store the primary model.
        return owner.getSuperTypes().stream().map(model::findType)
                .filter(CSharpImmutableBehaviorRenderModel::hasCalls).collect(Collectors.toList());
    }

    private Map<String, CallPlan> planCalls(Type owner) {
        var cached = callPlans.get(owner);
        if (cached != null) return cached;
        var parents = behaviorBases(owner).stream().map(this::planCalls).collect(Collectors.toList());
        var plans = new HashMap<String, CallPlan>();
        String contract = callsName(model.typeName(owner));
        for (var method : owner.getEffectiveMethods()) {
            if (CSharpCapabilities.isObjectOverride(owner, method)) continue;
            String signature = signatures.slot(owner, method);
            var inherited = new LinkedHashMap<String, CallSlot>();
            var visible = new LinkedHashMap<String, CallSlot>();
            for (var parent : parents) {
                var call = parent.get(signature);
                if (call == null) continue;
                call.inherited.forEach(slot -> inherited.putIfAbsent(slot.contract, slot));
                inherited.putIfAbsent(call.selected.contract, call.selected);
                visible.putIfAbsent(call.selected.contract, call.selected);
            }
            String returnType = signatures.typeName(owner, method.getReturnType());
            for (var slot : inherited.values()) {
                if (!slot.returnType.equals(returnType)
                        && !signatures.canWidenResult(owner, method.getReturnType(), slot.owner, slot.result)) {
                    throw new IllegalArgumentException("Cannot generate C# immutable behavior for "
                            + owner.getQualifiedName() + ": '" + signature + "' needs a compatible return type for its inherited call");
                }
            }
            // Distinct visible base calls need one nominal declaration on the composed interface.
            CallSlot closest = visible.size() == 1 ? visible.values().iterator().next() : null;
            boolean declaresOwn = closest == null || !closest.returnType.equals(returnType);
            CallSlot selected = declaresOwn
                    ? new CallSlot(owner, method.getReturnType(), contract, returnType) : closest;
            plans.put(signature, new CallPlan(selected, List.copyOf(inherited.values()), declaresOwn));
        }
        var result = Map.copyOf(plans);
        callPlans.put(owner, result);
        return result;
    }

    private static final class CallPlan {
        private final CallSlot selected;
        private final List<CallSlot> inherited;
        private final boolean declaresOwn;

        private CallPlan(CallSlot selected, List<CallSlot> inherited, boolean declaresOwn) {
            this.selected = selected;
            this.inherited = inherited;
            this.declaresOwn = declaresOwn;
        }
    }

    private static final class CallSlot {
        private final Type owner;
        private final CallableType result;
        private final String contract;
        private final String returnType;

        private CallSlot(Type owner, CallableType result, String contract, String returnType) {
            this.owner = owner;
            this.result = result;
            this.contract = contract;
            this.returnType = returnType;
        }
    }

    private static String bindingName(String javaBinding, Type owner) {
        String simpleName = javaBinding.substring(javaBinding.lastIndexOf('.') + 1).replace('$', '_');
        return CSharpRenderModel.identifier(simpleName, owner.getQualifiedName() + " behavior " + javaBinding);
    }

    private static IllegalArgumentException conflict(Type owner, String member) {
        return new IllegalArgumentException("Cannot generate C# immutable behavior for " + owner.getQualifiedName()
                + ": '" + member + "' conflicts with another C# member or signature");
    }
}
