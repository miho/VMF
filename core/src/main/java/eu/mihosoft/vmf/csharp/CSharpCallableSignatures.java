package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.CSharpCapabilities;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableMethod;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.CallableType;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Computes CLR callable identity independently of source spelling and nullable-reference annotations. */
final class CSharpCallableSignatures {
    private static final Map<String, String> FRAMEWORK_ALIASES = Map.ofEntries(
            Map.entry("global::System.Boolean", "bool"),
            Map.entry("global::System.Byte", "byte"),
            Map.entry("global::System.SByte", "sbyte"),
            Map.entry("global::System.Int16", "short"),
            Map.entry("global::System.UInt16", "ushort"),
            Map.entry("global::System.Int32", "int"),
            Map.entry("global::System.UInt32", "uint"),
            Map.entry("global::System.Int64", "long"),
            Map.entry("global::System.UInt64", "ulong"),
            Map.entry("global::System.IntPtr", "nint"),
            Map.entry("global::System.UIntPtr", "nuint"),
            Map.entry("global::System.Single", "float"),
            Map.entry("global::System.Double", "double"),
            Map.entry("global::System.Decimal", "decimal"),
            Map.entry("global::System.Char", "char"),
            Map.entry("global::System.String", "string"),
            Map.entry("global::System.Object", "object"));
    private static final Set<String> CSHARP_ALIASES = Set.copyOf(FRAMEWORK_ALIASES.values());

    private final CSharpRenderModel model;

    CSharpCallableSignatures(CSharpRenderModel model) {
        this.model = model;
    }

    static String methodName(Type owner, CallableMethod method) {
        return CSharpCapabilities.isObjectHashCode(method) ? "GetHashCode"
                : CSharpRenderModel.identifier(method.getName(), owner.getQualifiedName());
    }

    String slot(Type owner, CallableMethod method) {
        return methodName(owner, method)
                + method.getParameters().stream().map(parameter -> typeKey(owner, parameter.getType()))
                        .collect(Collectors.joining(",", "(", ")"));
    }

    void validate(Type owner) {
        var sourcesBySlot = new HashMap<String, String>();
        for (var method : owner.getEffectiveMethods()) {
            String slot = slot(owner, method);
            String source = method.getSourceSignature();
            String prior = sourcesBySlot.putIfAbsent(slot, source);
            if (prior != null && !prior.equals(source)) {
                throw new IllegalArgumentException("Cannot generate C# behavior for " + owner.getQualifiedName()
                        + "." + slot + ": distinct Java callables map to the same CLR signature ("
                        + prior + " and " + source + ")");
            }
        }
    }

    private String typeKey(Type owner, CallableType type) {
        switch (type.getKind()) {
            case VALUE:
                var value = type.getValueType();
                String spelling = model.callableValueTypeName(owner, value);
                String canonical = FRAMEWORK_ALIASES.getOrDefault(spelling, spelling);
                boolean isValue = CSHARP_ALIASES.contains(canonical)
                        ? !(canonical.equals("string") || canonical.equals("object"))
                        : model.externalCallableIsValueType(value);
                return canonical + (isValue && !value.isPrimitive() ? "?" : "");
            case ARRAY:
                return typeKey(owner, type.getElementType()) + "[]";
            case LIST:
                return "global::System.Collections.Generic.IList<" + typeKey(owner, type.getElementType()) + ">";
            case VOID:
                return "void";
            default:
                throw new IllegalStateException("Unsupported C# callable type " + type.getKind());
        }
    }
}
