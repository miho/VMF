package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Resolves the .NET host type for each external value used by the model. */
final class CSharpExternalRenderModel {
    private static final String REFERENCE_TYPE = "vmf:csharp:external-type";
    private static final String VALUE_TYPE = "vmf:csharp:external-value-type";
    private static final Pattern CLR_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private final ResolvedModel model;
    private final Map<String, Binding> bindings = new HashMap<>();
    private boolean requiresCodecs;

    CSharpExternalRenderModel(ResolvedModel model) {
        this.model = model;
    }

    Binding binding(ValueType type) {
        return bindings.computeIfAbsent(type.getExternalType(), this::resolve);
    }

    Binding snapshotBinding(ValueType type) {
        Binding binding = binding(type);
        requiresCodecs |= !binding.isBuiltIn();
        return binding;
    }

    boolean requiresCodecs() {
        return requiresCodecs;
    }

    private Binding resolve(String sourceName) {
        if (sourceName.equals("java.util.UUID")) return new Binding("global::System.Guid", true, true);
        var mappings = model.externalAnnotations(sourceName).stream()
                .filter(annotation -> annotation.getKey().equals(VALUE_TYPE) || annotation.getKey().equals(REFERENCE_TYPE))
                .collect(java.util.stream.Collectors.toList());
        if (mappings.size() != 1 || !CLR_NAME.matcher(mappings.get(0).getValue()).matches()) {
            throw new IllegalArgumentException("Cannot generate C# for " + sourceName
                    + ": declare one fully qualified CLR type with " + REFERENCE_TYPE + " or " + VALUE_TYPE);
        }
        var mapping = mappings.get(0);
        return new Binding("global::" + mapping.getValue(), mapping.getKey().equals(VALUE_TYPE), false);
    }

    static final class Binding {
        private final String typeName;
        private final boolean valueType;
        private final boolean builtIn;

        private Binding(String typeName, boolean valueType, boolean builtIn) {
            this.typeName = typeName;
            this.valueType = valueType;
            this.builtIn = builtIn;
        }

        String getTypeName() { return typeName; }
        boolean isValueType() { return valueType; }
        boolean isBuiltIn() { return builtIn; }
    }
}
