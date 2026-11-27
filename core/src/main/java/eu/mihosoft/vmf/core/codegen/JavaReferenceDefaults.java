package eu.mihosoft.vmf.core.codegen;

import eu.mihosoft.vmf.core.ModelType;
import eu.mihosoft.vmf.core.Prop;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ReferenceSetDefault;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/** Recognizes fresh singleton VMF defaults without evaluating the Java expression. */
final class JavaReferenceDefaults {
    private static final String MODEL = "([A-Za-z_][A-Za-z0-9_]*)";
    private static final String LIST = "\\s*java\\.util\\.List\\.of\\s*\\(\\s*" + MODEL + "\\s*\\.\\s*";
    private static final String END = "\\s*\\)\\s*";
    private static final Pattern INSTANCE = Pattern.compile(LIST + "newInstance\\s*\\(\\s*\\)" + END);
    private static final Pattern BUILDER = Pattern.compile(LIST + "newBuilder\\s*\\(\\s*\\)\\s*\\.\\s*with"
            + MODEL + "\\s*\\(\\s*(\"(?:[^\"\\\\\\r\\n]|\\\\.)*\")" + END + "\\.\\s*build\\s*\\(\\s*\\)" + END);

    private JavaReferenceDefaults() { }

    static ReferenceSetDefault parse(String source, ModelType target) {
        if (target == null || target.isInterfaceOnly() || !target.getTypeParameters().isEmpty()
                || !target.getImplementz().isEmpty() || !target.getImplementation().getConstructorDelegations().isEmpty()
                || target.getImplementation().getProperties().stream().anyMatch(Prop::hasDeclaredDefaultValue)) return null;

        var instance = INSTANCE.matcher(source);
        if (instance.matches() && target.getTypeName().equals(instance.group(1))) {
            return new ReferenceSetDefault(target.getTypeName(), Map.of());
        }

        var builder = BUILDER.matcher(source);
        if (!builder.matches() || !target.getTypeName().equals(builder.group(1))) return null;
        var field = target.getImplementation().getProperties().stream().filter(property ->
                !property.isCollectionType() && property.getMetadataTypeName().equals(String.class.getName())
                        && property.getNameWithUpperCase().equals(builder.group(2))).findFirst().orElse(null);
        if (field == null) return null;

        var type = new ValueType(ValueType.Kind.STRING, null, String.class.getName(), false);
        var value = JavaScalarDefaults.parse(builder.group(3), type);
        return value != null && value.getValue() instanceof String
                && StandardCharsets.UTF_8.newEncoder().canEncode((String) value.getValue())
                ? new ReferenceSetDefault(target.getTypeName(), Map.of(field.getName(), value)) : null;
    }
}
