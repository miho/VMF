package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class CSharpExecutableDefaultRenderModel {
    private CSharpExecutableDefaultRenderModel() {
    }

    static Map<String, String> method(CSharpRenderModel model, Type owner, Property property, Set<String> members,
                                      boolean hidesInheritedAccessor) {
        String name = CSharpRenderModel.propertyName(owner, property);
        String forwarded = model.projectedDefaultCall(owner, property);
        String binding = forwarded.isEmpty() ? "CreateDefault" + name : "";
        String accessor = accessor(name);
        if ((!binding.isEmpty() && !members.add(binding)) || !members.add(accessor)) {
            throw new IllegalArgumentException("Cannot generate C# executable default '" + owner.getQualifiedName()
                    + "." + property.getName() + "': a generated default method conflicts with another member");
        }
        String type = model.typeName(property.getValueType()) + (property.getValueType().isPrimitive() ? "" : "?");
        return Map.of("type", type, "binding", binding, "accessor", accessor,
                "expression", forwarded.isEmpty() ? binding + "()" : forwarded,
                "modifier", hidesInheritedAccessor ? "new " : "");
    }

    static String accessor(String member) {
        return "__vmfDefault" + member;
    }

    static boolean hasDefault(Property property) {
        return property.getScalarDefault().isPresent() || CSharpCapabilities.bindsExecutableDefault(property);
    }

    enum DeferredConstructor { SCALAR, MIXED }

    static Map<Type, DeferredConstructor> mutableFamilies(List<Type> types, CSharpHierarchyRenderModel hierarchy) {
        var byRoot = types.stream().filter(type -> !type.isImmutable()).collect(Collectors.groupingBy(type -> root(type, hierarchy)));
        var result = new HashMap<Type, DeferredConstructor>();
        for (var family : byRoot.values()) {
            if (family.size() < 2) continue;
            var fields = family.stream().flatMap(type -> type.getEffectiveProperties().stream()).collect(Collectors.toList());
            var names = family.stream().map(Type::getName).collect(Collectors.toSet());
            var referenceDefaults = fields.stream().filter(CSharpCapabilities::hasReferenceSetDefault).collect(Collectors.toList());
            boolean hasReferences = !referenceDefaults.isEmpty();
            boolean hasManaged = fields.stream().anyMatch(CSharpCapabilities::hasManagedCollectionDefault);
            boolean projected = referenceDefaults.stream().anyMatch(field -> !names.contains(field.getDeclaringType()));
            boolean effectiveOrder = projected || hasManaged
                    || fields.stream().anyMatch(CSharpCapabilities::bindsExecutableDefault)
                    || hasReferences && family.stream().anyMatch(type -> type.getConstructionHook().isPresent());
            if (effectiveOrder) {
                var constructor = hasReferences || hasManaged ? DeferredConstructor.MIXED : DeferredConstructor.SCALAR;
                family.forEach(type -> result.put(type, constructor));
            }
        }
        return Map.copyOf(result);
    }

    private static Type root(Type type, CSharpHierarchyRenderModel hierarchy) {
        Type parent = hierarchy.primaryBase(type);
        return parent == null ? type : root(parent, hierarchy);
    }

    static boolean hidesAccessor(Type owner, Property property, CSharpHierarchyRenderModel hierarchy) {
        for (Type parent = hierarchy.primaryBase(owner); parent != null; parent = hierarchy.primaryBase(parent)) {
            if (parent.getProperties().stream().anyMatch(candidate -> candidate.getName().equals(property.getName())
                    && CSharpCapabilities.bindsExecutableDefault(candidate))) return true;
        }
        return false;
    }
}
