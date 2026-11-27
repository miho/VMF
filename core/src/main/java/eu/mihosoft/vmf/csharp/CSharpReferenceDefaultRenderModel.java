package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Describes an identity-set seed and its cached use by one generated owner. */
final class CSharpReferenceDefaultRenderModel {
    private CSharpReferenceDefaultRenderModel() {
    }

    static Map<String, String> prepare(CSharpRenderModel source, Type owner, Property property, Set<String> members) {
        String name = CSharpRenderModel.propertyName(owner, property);
        String forwarded = source.projectedDefaultCall(owner, property);
        String binding = forwarded.isEmpty() && CSharpCapabilities.bindsReferenceSetDefault(property)
                ? "CreateDefault" + name : "";
        String accessor = CSharpExecutableDefaultRenderModel.accessor(name);
        String value = source.typeName(property.getValueType());
        if ((!binding.isEmpty() && !members.add(binding)) || !members.add(accessor)) {
            throw new IllegalArgumentException("Cannot generate C# reference-set default '" + owner.getQualifiedName()
                    + "." + property.getName() + "': a generated default method conflicts with another member");
        }
        String expression = forwarded;
        if (expression.isEmpty()) {
            String seed = binding.isEmpty() ? seed(source, property, value) : binding + "()";
            expression = "global::Vmf.Runtime.ReferenceCollectionDefaults.Capture(" + seed + ")";
        }
        return Map.of("name", name, "key", CSharpSnapshotRenderModel.literal(property.getName()),
                "element", value, "binding", binding, "expression", expression, "accessor", accessor, "cache", cache(name));
    }

    static Map<String, String> assignment(Type owner, Property property) {
        String name = CSharpRenderModel.propertyName(owner, property);
        return Map.of("referenceDefault", CSharpSnapshotRenderModel.literal(property.getName()), "cache", cache(name),
                "accessor", CSharpExecutableDefaultRenderModel.accessor(name), "name", name);
    }

    static Map<String, String> builderCache(CSharpRenderModel source, Type owner, Property property) {
        String name = CSharpRenderModel.propertyName(owner, property);
        return Map.of("element", source.typeName(property.getValueType()), "cache", cache(name),
                "accessor", CSharpExecutableDefaultRenderModel.accessor(name));
    }

    private static String seed(CSharpRenderModel source, Property property, String value) {
        var recipe = property.getReferenceSetDefault().orElseThrow();
        var target = source.findType(recipe.getModelType());
        String concreteType = source.typeName(target);
        String instance;
        if (recipe.getAssignments().isEmpty()) {
            instance = "new " + concreteType + "()";
        } else {
            String assignments = recipe.getAssignments().entrySet().stream().map(field -> ".With"
                    + CSharpRenderModel.identifier(field.getKey(), target.getQualifiedName())
                    + "(" + CSharpDefaultRenderModel.literal(field.getValue()) + ")").collect(Collectors.joining());
            instance = concreteType + ".NewBuilder()" + assignments + ".Build()";
        }
        return "new " + value + "[] { " + instance + " }";
    }

    static String cache(String member) {
        return "__vmfCaptured" + member;
    }

}
