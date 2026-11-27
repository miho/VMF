package eu.mihosoft.vmf.csharp;

import eu.mihosoft.vmf.core.codegen.ResolvedModel;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.MetadataAnnotation;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Property;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.Type;
import eu.mihosoft.vmf.core.codegen.ResolvedModel.ValueType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static eu.mihosoft.vmf.csharp.CSharpSnapshotRenderModel.literal;

/** Emits static metadata from the resolved declarations without constructing a model object. */
final class CSharpTypeMetadataRenderModel {
    private static final String RUNTIME = "global::Vmf.Runtime.";

    private final ResolvedModel model;
    private final CSharpRenderModel names;

    private CSharpTypeMetadataRenderModel(ResolvedModel model, CSharpRenderModel names) {
        this.model = model;
        this.names = names;
    }

    static List<Map<String, Object>> prepare(ResolvedModel model, CSharpRenderModel names) {
        var renderer = new CSharpTypeMetadataRenderModel(model, names);
        return model.getTypes().stream().sorted(Comparator.comparing(Type::getName))
                .map(renderer::type).collect(Collectors.toUnmodifiableList());
    }

    private Map<String, Object> type(Type type) {
        int arity = type.getTypeParameters().size();
        String runtime = names.typeName(type) + (arity == 0 ? "" : "<" + ",".repeat(arity - 1) + ">");
        var parameters = type.getTypeParameters().stream()
                .map(parameter -> "new(" + literal(parameter.getName()) + ", "
                        + valueTypes(type, parameter.getBounds()) + ")").collect(Collectors.toList());
        var fields = type.getEffectiveProperties().stream().map(property -> property(type, property))
                .collect(Collectors.toUnmodifiableList());
        return Map.of(
                "name", literal(type.getQualifiedName()),
                "wireName", literal(type.getWireTypeName()),
                "runtimeType", runtime,
                "interfaceOnly", Boolean.toString(type.isInterfaceOnly()),
                "immutable", Boolean.toString(type.isImmutable()),
                "parameters", array("ModelTypeParameter", parameters),
                "parents", valueTypes(type, type.getSuperTypeApplications()),
                "fields", fields,
                "annotations", annotations(type.getAnnotations()));
    }

    private String property(Type owner, Property property) {
        var arguments = new ArrayList<>(List.of(
                literal(property.getName()),
                literal(model.findType(property.getDeclaringType()).orElseThrow().getQualifiedName()),
                property.getKind() == Property.Kind.CONTAINER ? "null" : literal(property.getWireName()),
                RUNTIME + "ModelFieldKind." + enumName(property.getKind().name()),
                valueType(owner, property.getValueType()),
                Integer.toString(property.getLowerBound()), Integer.toString(property.getUpperBound()),
                "collection: " + property.isCollection(), "ordered: " + property.isOrdered(),
                "unique: " + property.isUnique(), "getterOnly: " + property.isGetterOnly()));
        if (property.getKeyType() != null) arguments.add("keyType: " + valueType(owner, property.getKeyType()));
        property.getEntryKey().ifPresent(key -> arguments.add("keyPropertyName: " + literal(key.getPropertyName())));
        if (!property.getOppositeName().isEmpty()) arguments.add("oppositeName: " + literal(property.getOppositeName()));
        if (!property.getAnnotations().isEmpty()) arguments.add("annotations: " + annotations(property.getAnnotations()));
        return "new(" + String.join(", ", arguments) + CSharpSchemaRenderModel.propertyMetadata(property) + ")";
    }

    private String valueType(Type owner, ValueType value) {
        var kind = value.getKind();
        String name;
        switch (kind) {
            case MODEL:
                name = model.findType(value.getModelType()).orElseThrow().getQualifiedName();
                break;
            case PARAMETER:
                name = value.getParameterName();
                break;
            case EXTERNAL:
                name = value.getExternalType();
                break;
            default:
                name = kind.name().toLowerCase(Locale.ROOT);
                break;
        }
        return "new " + RUNTIME + "ModelTypeUse(" + RUNTIME + "ModelValueKind." + enumName(kind.name()) + ", "
                + literal(name) + (value.isPrimitive() ? ", primitive: true" : "")
                + (value.getArguments().isEmpty() ? "" : ", arguments: " + valueTypes(owner, value.getArguments()))
                + (kind == ValueType.Kind.PARAMETER ? ", declaringType: " + literal(owner.getQualifiedName()) : "") + ")";
    }

    private String valueTypes(Type owner, List<ValueType> values) {
        return array("ModelTypeUse", values.stream().map(value -> valueType(owner, value)).collect(Collectors.toList()));
    }

    private static String annotations(List<MetadataAnnotation> values) {
        return array("ModelAnnotation", values.stream().map(value ->
                "new(" + literal(value.getKey()) + ", " + literal(value.getValue()) + ")").collect(Collectors.toList()));
    }

    private static String array(String element, List<String> entries) {
        return entries.isEmpty() ? "global::System.Array.Empty<" + RUNTIME + element + ">()"
                : "new " + RUNTIME + element + "[] { " + String.join(", ", entries) + " }";
    }

    private static String enumName(String original) {
        return original.charAt(0) + original.substring(1).toLowerCase(Locale.ROOT);
    }
}
