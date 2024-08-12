package eu.mihosoft.vmf.jackson;

import eu.mihosoft.vmf.runtime.core.Immutable;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;


import java.util.HashMap;
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

            boolean isToBeExcludedFromSerialization = isToBeExcludedFromSerialization(property);


            if (isToBeExcludedFromSerialization) {
                // skip
            } else {
                properties.put(getFieldNameForProperty(property), getPropertySchema(property));
            }
        }

        schema.put("definitions", generateDefinitions(type));

        return schema;
    }

    private static Map<String, Object> getPropertySchema(Property property) {
        Map<String, Object> propertySchema = new HashMap<>();

        // if property is not contained in the model, skip it (if model type or list and model type, keep otherwise)
        boolean isToBeExcludeFromSerialization = isToBeExcludedFromSerialization(property);

        if (isToBeExcludeFromSerialization) {
            // skip
        } else if (isValueType(property.getType())) {
            propertySchema.put("type", mapValueType(property.getType()));
        } else if (property.getType().isModelType() && !property.getType().isListType()) {
            // Complex object, reference definition
            propertySchema.put("$ref", "#/definitions/" + property.getType().getName());
        } else if (property.getType().isListType()) {
            // Recognize VList types as arrays in JSON Schema

            propertySchema.put("type", "array");
            Map<String, Object> itemsSchema = new HashMap<>();

            // Handle polymorphic types with oneOf
            Type elementType = VMFTypeUtils.forClass(property.getType().getElementTypeName().get());
            if (VMFTypeUtils.getSubTypes(elementType).size() > 0) {

                var typesToChooseFrom = VMFTypeUtils.getSubTypes(elementType);
                typesToChooseFrom.add(elementType);

                // remove all interface-only types
                typesToChooseFrom.removeIf(Type::isInterfaceOnly);

                itemsSchema.put("oneOf", typesToChooseFrom.stream().map(subType -> {
                    Map<String, Object> ref = new HashMap<>();
                    ref.put("$ref", "#/definitions/" + subType.getName());
                    return ref;
                }).toArray());
            } else {
                itemsSchema.put("$ref", "#/definitions/" + property.getType().getElementTypeName().get());
            }

            propertySchema.put("items", itemsSchema);

        } else if (VMFTypeUtils.isEnum(property.getType())) {
            // Enum type
            propertySchema.put("type", "string");
            propertySchema.put("enum", VMFTypeUtils.getEnumConstants(property.getType()));
        } else {
            // Handle other types or fallback
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

                boolean toBeExcludedFromSerialization = isToBeExcludedFromSerialization(property);

                if (toBeExcludedFromSerialization) {
                    //
                } else {
                    properties.put(getFieldNameForProperty(property), getPropertySchema(property));
                }
            }

            // Handle polymorphism using allOf to include super types if necessary
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

    /**
     * Check if the property should be excluded from serialization.
     *
     * @param property the property to check
     * @return {@code true} if the property should be excluded from serialization, {@code false} otherwise
     */
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

        boolean toBeExcludedFromSerialization = property.getType().isModelType() && !immutableType && !VMFTypeUtils.isParentOfPropContainer(property);
        return toBeExcludedFromSerialization;
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
}
