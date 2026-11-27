package eu.mihosoft.vmf.core.codegen;

import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/** Bounds projected defaults to independent, unchanged slots and data-only secondary roles. */
final class PortableReferenceDefaultComposition {
    private PortableReferenceDefaultComposition() { }

    static boolean supports(ResolvedModel model, Type owner) {
        var component = inheritanceComponent(model, owner);
        return component.stream().anyMatch(type -> projectsReferenceDefaults(model, type))
                && component.stream().allMatch(type -> supportsSlots(model, type));
    }

    private static Set<Type> inheritanceComponent(ResolvedModel model, Type owner) {
        var types = new HashSet<Type>();
        var next = new ArrayDeque<Type>();
        next.add(owner);
        while (!next.isEmpty()) {
            var current = next.remove();
            if (!types.add(current)) continue;
            current.getSuperTypes().forEach(name -> model.findType(name).ifPresent(next::add));
            model.getTypes().stream().filter(type -> type.getSuperTypes().contains(current.getName())).forEach(next::add);
        }
        return types;
    }

    private static boolean projectsReferenceDefaults(ResolvedModel model, Type type) {
        return type.getSuperTypes().stream().skip(1).map(model::findType)
                .flatMap(java.util.Optional::stream).flatMap(parent -> parent.getEffectiveProperties().stream())
                .anyMatch(CSharpCapabilities::hasReferenceSetDefault);
    }

    private static boolean supportsSlots(ResolvedModel model, Type type) {
        if (type.isImmutable() || type.isInterfaceOnly() || !type.getTypeParameters().isEmpty()) return false;
        var names = new HashSet<String>();
        var bases = type.getSuperTypes();
        for (int index = 0; index < bases.size(); index++) {
            var parent = model.findType(bases.get(index)).orElse(null);
            // The concrete primary spine owns behavior dispatch. Secondary roles contribute data.
            if (parent == null
                    || index > 0 && (parent.getConstructionHook().isPresent() || !parent.getEffectiveMethods().isEmpty())
                    || !parent.getEffectiveProperties().stream().allMatch(property -> names.add(property.getName()))) {
                return false;
            }
        }
        return type.getProperties().stream().allMatch(property -> !names.contains(property.getName())
                && (!CSharpCapabilities.hasReferenceSetDefault(property) || type.getSuperTypes().isEmpty()));
    }
}
