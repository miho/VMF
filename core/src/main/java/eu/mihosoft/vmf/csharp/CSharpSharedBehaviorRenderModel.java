package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Emits one typed default provider for a Java binding class shared by composed roles. */
final class CSharpSharedBehaviorRenderModel {
    static final String CONTAINER = "ModelBehaviorBindings";

    private final CSharpRenderModel model;
    private final CSharpBehaviorRenderModel signatures;
    private final Map<Type, Map<String, String>> accessors = new HashMap<>();
    private final List<Map<String, Object>> bindings;

    CSharpSharedBehaviorRenderModel(CSharpRenderModel model, CSharpBehaviorRenderModel signatures, ResolvedModel resolved) {
        this.model = model;
        this.signatures = signatures;
        Map<String, Set<Type>> ownersByBinding = new TreeMap<>();
        for (Type type : resolved.getTypes()) {
            CSharpCapabilities.sharedBehaviorBindings(resolved, type).forEach((binding, roles) -> {
                Set<Type> owners = ownersByBinding.computeIfAbsent(binding, unused -> new LinkedHashSet<>());
                roles.stream().map(model::findType).forEach(owners::add);
                if (type.getDeclaredMethods().stream().anyMatch(method -> method.getJavaBinding().equals(binding))
                        || type.getConstructionHook().filter(hook -> hook.getDeclaringType().equals(type.getName())
                            && hook.getJavaBinding().equals(binding)).isPresent()) {
                    owners.add(type);
                }
            });
        }
        var names = CSharpDefaultBehaviorNames.disambiguate(ownersByBinding.keySet(),
                binding -> CSharpRenderModel.identifier(CSharpDefaultBehaviorNames.simpleName(binding), binding));
        var emittedNames = new HashSet<String>(Set.of(CONTAINER));
        var rendered = new ArrayList<Map<String, Object>>();
        ownersByBinding.forEach((binding, owners) -> {
            String name = names.get(binding);
            String accessor = "__vmfGet" + name;
            var generatedMembers = List.of(name, "Create" + name, accessor, "__vmf" + name + "State", "__vmf" + name + "ByOwner");
            if (!generatedMembers.stream().allMatch(emittedNames::add)) {
                throw new IllegalArgumentException("Cannot generate C# shared behavior for " + binding
                        + ": binding name conflicts with another generated member");
            }
            var sortedOwners = owners.stream().sorted(Comparator.comparing(Type::getName)).collect(Collectors.toList());
            for (Type owner : sortedOwners) {
                accessors.computeIfAbsent(owner, unused -> new HashMap<>()).put(binding, CONTAINER + "." + accessor);
            }
            rendered.add(prepareBinding(binding, name, accessor, sortedOwners));
        });
        bindings = List.copyOf(rendered);
    }

    List<Map<String, Object>> getBindings() { return bindings; }

    String accessor(Type type, String binding) { return accessors.getOrDefault(type, Map.of()).getOrDefault(binding, ""); }

    private Map<String, Object> prepareBinding(String binding, String name, String accessor, List<Type> owners) {
        var methodsBySlot = new LinkedHashMap<String, List<Map.Entry<Type, CallableMethod>>>();
        for (Type owner : owners) {
            for (CallableMethod method : owner.getDeclaredMethods()) {
                if (!method.getJavaBinding().equals(binding)) continue;
                methodsBySlot.computeIfAbsent(signatures.slot(owner, method), unused -> new ArrayList<>())
                        .add(Map.entry(owner, method));
            }
        }
        var sharedMethods = new ArrayList<Map<String, String>>();
        var bridges = new ArrayList<Map<String, String>>();
        for (var slot : methodsBySlot.entrySet()) {
            var declarations = slot.getValue();
            if (declarations.size() < 2) continue;
            String sourceSignature = declarations.get(0).getValue().getSourceSignature();
            if (declarations.stream().anyMatch(entry -> !sourceSignature.equals(entry.getValue().getSourceSignature()))) {
                throw incompatible(binding, slot.getKey(), "distinct Java callables map to the same CLR signature");
            }
            var first = declarations.stream().filter(candidate -> declarations.stream().allMatch(target ->
                    signatures.canWidenResult(candidate.getKey(), candidate.getValue().getReturnType(),
                            target.getKey(), target.getValue().getReturnType())))
                    .findFirst().orElseThrow(() -> incompatible(binding, slot.getKey(),
                            "the shared callable has incompatible CLR result types"));
            CallableMethod method = first.getValue();
            String methodName = CSharpRenderModel.identifier(method.getName(), binding);
            var shared = signatures.prepareMethod(first.getKey(), method, methodName, "object");
            for (var declaration : declarations) {
                Type owner = declaration.getKey();
                CallableMethod actual = declaration.getValue();
                String caller = model.isCompanionType(owner) && model.isBoundBehaviorRole(owner)
                        ? model.modelUseName(owner) : model.typeName(owner);
                var bridge = new HashMap<>(signatures.prepareMethod(owner, actual, methodName, caller));
                bridge.put("behaviorType", defaultContract(owner, binding));
                String arguments = bridge.get("parameterArguments");
                bridge.put("arguments", "(object)caller" + (arguments.isEmpty() ? "" : ", " + arguments));
                bridges.add(Map.copyOf(bridge));
            }
            sharedMethods.add(shared);
        }
        return Map.of("name", name, "accessor", accessor,
                "bases", owners.stream().map(owner -> defaultContract(owner, binding)).collect(Collectors.joining(", ")),
                "methods", List.copyOf(sharedMethods), "bridges", List.copyOf(bridges));
    }

    private String defaultContract(Type owner, String binding) {
        return model.typeName(owner) + "." + model.behaviorProviderMember(owner, binding, "DefaultBehavior");
    }

    private static IllegalArgumentException incompatible(String binding, String slot, String description) {
        return new IllegalArgumentException("Cannot generate C# shared behavior for " + binding + "." + slot + ": " + description);
    }
}
