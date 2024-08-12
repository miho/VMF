package eu.mihosoft.vmf.jackson;

import eu.mihosoft.vmf.runtime.core.Immutable;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JsonSchemaGenerator {

    public static Map<String, Object> generateSchema(Class<? extends VObject> modelClass) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("title", modelClass.getSimpleName());
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        schema.put("properties", properties);

        Type type = VMFTypeUtils.forClass(modelClass);

        for (Property property : type.reflect().properties()) {
            if (!isToBeExcludedFromSerialization(property)) {
                properties.put(getFieldNameForProperty(property), getPropertySchema(property));
            }
        }

        schema.put("definitions", generateDefinitions(type));

        return schema;
    }

    private static Map<String, Object> getPropertySchema(Property property) {
        Map<String, Object> propertySchema = new HashMap<>();

        if (isToBeExcludedFromSerialization(property)) {
            return propertySchema;
        } else if (isValueType(property.getType())) {
            propertySchema.put("type", mapValueType(property.getType()));
        } else if (property.getType().isModelType() && !property.getType().isListType()) {
            // Handle polymorphic types
            Type elementType = VMFTypeUtils.forClass(property.getType().getName());
            if (!VMFTypeUtils.getSubTypes(elementType).isEmpty()) {
                var typesToChooseFrom = VMFTypeUtils.getSubTypes(elementType);
                typesToChooseFrom.add(elementType);

                typesToChooseFrom.removeIf(Type::isInterfaceOnly);

                propertySchema.put("oneOf", typesToChooseFrom.stream().map(subType -> {
                    Map<String, Object> typeSchema = new HashMap<>();
                    typeSchema.put("$ref", "#/definitions/" + subType.getName());
                    typeSchema.put("properties", Map.of("@vmf-type",
                            Map.of("type", "string", "enum",
                                    List.of(getTypeAlias(subType)), "readOnly", true))
                    );
                    typeSchema.put("required", new String[]{"@vmf-type"});
                    return typeSchema;
                }).toArray());
            } else {
                propertySchema.put("$ref", "#/definitions/" + property.getType().getName());
            }
        } else if (property.getType().isListType()) {
            propertySchema.put("type", "array");
            Map<String, Object> itemsSchema = new HashMap<>();

            // Handle polymorphic types with oneOf and add @vmf-type as a required property
            Type elementType = VMFTypeUtils.forClass(property.getType().getElementTypeName().get());
            if (!VMFTypeUtils.getSubTypes(elementType).isEmpty()) {
                var typesToChooseFrom = VMFTypeUtils.getSubTypes(elementType);
                typesToChooseFrom.add(elementType);

                typesToChooseFrom.removeIf(Type::isInterfaceOnly);

                itemsSchema.put("oneOf", typesToChooseFrom.stream().map(subType -> {
                    Map<String, Object> typeSchema = new HashMap<>();
                    typeSchema.put("$ref", "#/definitions/" + subType.getName());
                    typeSchema.put("properties", Map.of("@vmf-type",
                            Map.of("type", "string", "enum",
                                    List.of(getTypeAlias(subType)), "readOnly", true))
                    );
                    typeSchema.put("required", new String[]{"@vmf-type"});
                    return typeSchema;
                }).toArray());
            } else {
                itemsSchema.put("$ref", "#/definitions/" + property.getType().getElementTypeName().get());
            }

            propertySchema.put("items", itemsSchema);
        } else if (VMFTypeUtils.isEnum(property.getType())) {
            propertySchema.put("type", "string");
            propertySchema.put("enum", VMFTypeUtils.getEnumConstants(property.getType()));
        } else {
            propertySchema.put("type", "string");
        }

        return propertySchema;
    }


    private static Map<String, Object> generateDefinitions(Type type) {
        Map<String, Object> definitions = new HashMap<>();
        for (Type subType : type.reflect().allTypes()) {
            if (subType.isInterfaceOnly()) continue;

            Map<String, Object> definition = new HashMap<>();
            definition.put("type", "object");

            Map<String, Object> properties = new HashMap<>();
            for (Property property : subType.reflect().properties()) {
                if (!isToBeExcludedFromSerialization(property)) {
                    properties.put(getFieldNameForProperty(property), getPropertySchema(property));
                }
            }

            if (!subType.superTypes().isEmpty()) {
                definition.put("allOf", subType.superTypes().stream().map(superType -> {
                    Map<String, Object> ref = new HashMap<>();
                    ref.put("$ref", "#/definitions/" + superType.getName());
                    return ref;
                }).toArray());
            }

            definition.put("properties", properties);
            definitions.put(subType.getName(), definition);
        }
        return definitions;
    }

    private static boolean isToBeExcludedFromSerialization(Property property) {
        boolean immutableType = false;
        try {
            immutableType = isValueType(property.getType()) ||
                    (
                            Immutable.class.isAssignableFrom(Class.forName(property.getType().isListType()
                                    ? property.getType().getElementTypeName().get()
                                    : property.getType().getName()))
                    );
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        return property.getType().isModelType() && !immutableType && !VMFTypeUtils.isParentOfPropContainer(property);
    }

    private static String mapValueType(Type type) {
        if (isInteger(type)) return "integer";
        if (isBoolean(type)) return "boolean";
        if (isDouble(type) || isFloat(type)) return "number";
        return "string";
    }

    public static boolean isInteger(Type type) {
        return type.getName().equals("java.lang.Integer") || type.getName().equals("int") ||
                type.getName().equals("java.lang.Short") || type.getName().equals("short") ||
                type.getName().equals("java.lang.Long") || type.getName().equals("long");
    }

    public static boolean isBoolean(Type type) {
        return type.getName().equals("java.lang.Boolean") || type.getName().equals("boolean");
    }

    public static boolean isFloat(Type type) {
        return type.getName().equals("java.lang.Float") || type.getName().equals("float");
    }

    public static boolean isDouble(Type type) {
        return type.getName().equals("java.lang.Double") || type.getName().equals("double");
    }

    public static boolean isValueType(Type type) {
        String clsName = type.getName();

        return clsName.equals("int") || clsName.equals("java.lang.Integer") ||
                clsName.equals("short") || clsName.equals("java.lang.Short") ||
                clsName.equals("long") || clsName.equals("java.lang.Long") ||
                clsName.equals("boolean") || clsName.equals("java.lang.Boolean") ||
                clsName.equals("float") || clsName.equals("java.lang.Float") ||
                clsName.equals("double") || clsName.equals("java.lang.Double") ||
                clsName.equals("java.lang.String");
    }

    private static String getFieldNameForProperty(Property p) {
        var a = p.annotationByKey("vmf:jackson:rename");
        return a.isPresent() ? a.get().getValue() : p.getName();
    }

    private static String getTypeAlias(Type type) {
        // This method would return the alias for a given type.
        // For example, if the type is "Employee", it should return "employee".
        // You might need to implement a mapping logic here, depending on how you want to handle aliases.
        return type.getName();
    }
}
