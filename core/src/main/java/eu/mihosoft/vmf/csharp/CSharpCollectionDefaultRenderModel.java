package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Lowers typed literal and explicitly bound managed collection defaults. */
final class CSharpCollectionDefaultRenderModel {
    private static final String GENERIC = "global::System.Collections.Generic.";
    private static final String RUNTIME = "global::Vmf.Runtime.ManagedCollectionDefaults.";

    private CSharpCollectionDefaultRenderModel() {
    }

    static Map<String, String> method(CSharpRenderModel model, Type owner, Property property, Set<String> members) {
        String name = CSharpRenderModel.propertyName(owner, property);
        String forwarded = model.projectedDefaultCall(owner, property);
        boolean bound = forwarded.isEmpty() && CSharpCapabilities.bindsExecutableCollectionDefault(property);
        String binding = bound ? "CreateDefault" + name : "";
        String accessor = CSharpExecutableDefaultRenderModel.accessor(name);
        if ((bound && !members.add(binding)) || !members.add(accessor)) {
            throw new IllegalArgumentException("Cannot generate C# executable default '" + owner.getQualifiedName()
                    + "." + property.getName() + "': a generated default method conflicts with another member");
        }
        String element = CSharpScalarCollections.valueType(model, property);
        if (property.getKind() == Property.Kind.MAP) {
            element = GENERIC + "KeyValuePair<" + model.typeName(property.getKeyType()) + ", " + element + ">";
        }
        String storage = CSharpScalarCollections.mutableType(model, property);
        String create = "new " + storage + "(" + CSharpScalarCollections.constructorArguments(property) + ")";
        String values = !forwarded.isEmpty() ? forwarded : bound ? binding + "()" : literals(property);
        String expression = forwarded.isEmpty() ? copyInto(create, values) : forwarded;
        return Map.of("inputType", GENERIC + "IEnumerable<" + element + ">", "storage", storage,
                "binding", binding, "accessor", accessor, "values", values, "expression", expression,
                "modifier", model.hasInheritedManagedCollectionDefault(owner, property) ? "new " : "");
    }

    private static String literals(Property property) {
        var defaults = property.getCollectionDefault().orElseThrow();
        if (defaults.getValues().isEmpty()) return "[]";
        if (property.getKind() == Property.Kind.MAP) {
            String key = CSharpDefaultRenderModel.literal(defaults.getKeys().get(0));
            String value = CSharpDefaultRenderModel.literal(defaults.getValues().get(0));
            return "[new(" + key + ", " + value + ")]";
        }
        return defaults.getValues().stream().map(CSharpDefaultRenderModel::literal)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    static String copyInto(String receiver, String values) {
        return RUNTIME + "CopyInto(" + receiver + ", " + values + ")";
    }

    static String initialize(Property property, String receiver, String values) {
        return property.getKind() == Property.Kind.MAP
                ? RUNTIME + "ApplyBuilderMap(" + receiver + ", " + values + ", append: false)"
                : copyInto(receiver, values);
    }

    static String evaluate(String member) {
        return CSharpExecutableDefaultRenderModel.accessor(member) + "()";
    }
}
