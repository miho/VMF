package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.mihosoft.vmf.runtime.core.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static eu.mihosoft.vmf.jackson.VMFTypeUtils.getBuilderClass;

/**
 * A JSON schema generator for VMF models. This class can be used to generate a JSON schema for a VMF model class.
 * The schema is generated based on the model class and its properties. The schema is generated in draft-07 format.
 */
public class VMFJsonSchemaGenerator {
    public enum RUNTIME_TYPE {
        RELEASE, EXPERIMENTAL
    }

    private final Map<String, String> typeAliases        = new HashMap<>();
    private final Map<String, String> typeAliasesReverse = new HashMap<>();

    /**
     * Add a type alias to the module. This method is used to add a type alias that is used to determine the actual type
     * of an object during serialization/deserialization. The type alias is used to map a type name to a class name.
     *
     * <p>Example:
     * <pre>{@code
     * module.addTypeAlias("person", "eu.mihosoft.vmf.jackson.test.Person");
     * }</pre>
     * <p>This example adds a type alias {@code person} that maps to the class {@code eu.mihosoft.vmf.jackson.test.Person}.
     */
    public void addTypeAlias(String alias, String className) {
        typeAliases.put(alias, className);
        typeAliasesReverse.put(className, alias);
    }

    /**
     * Returns the type aliases of the module.
     * @return the type aliases of the module
     */
    public Map<String, String> getTypeAliases() {
        return Collections.unmodifiableMap(typeAliases);
    }

    /**
     * Returns the reverse type aliases of the module.
     */
    public Map<String, String> getTypeAliasesReverse() {
        return Collections.unmodifiableMap(typeAliasesReverse);
    }

    /**
     * Generate a JSON schema for the specified model class. The schema is generated based on the model class and its
     * properties. The schema is generated in draft-07 format.
     * @param modelClass the model class for which to generate the schema
     * @return the generated schema as a map
     */
    public Map<String, Object> generateSchema(Class<? extends VObject> modelClass) {
        return _generateSchema(modelClass);
    }

    /**
     * Generate a JSON schema for the specified model class. The schema is generated based on the model class and its
     * properties. The schema is generated in draft-07 format.
     * @param modelClass the model class for which to generate the schema
     * @return the generated schema as a map
     */
    public String generateSchemaAsString(Class<? extends VObject> modelClass) {
        var schema = generateSchema(modelClass);

        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Add a type alias to the module. This method is used to add a type alias that is used to determine the actual type
     * of an object during serialization/deserialization. The type alias is used to map a type name to a class name.
     *
     * <p>Example:
     * <pre>{@code
     * module.addTypeAlias("person", "eu.mihosoft.vmf.jackson.test.Person");
     * }</pre>
     * <p>This example adds a type alias {@code person} that maps to the class {@code eu.mihosoft.vmf.jackson.test.Person}.
     */
    public VMFJsonSchemaGenerator withTypeAlias(String alias, String className) {
        addTypeAlias(alias, className);
        return this;
    }

    /**
     * Add multiple type aliases to the module. This method is used to add multiple type aliases at once.
     * <p>Example:
     * <pre>{@code
     * Map<String, String> typeAliases = new HashMap<>();
     * typeAliases.put("person", "eu.mihosoft.vmf.jackson.test.Person");
     * typeAliases.put("employee", "eu.mihosoft.vmf.jackson.test.Employee");
     * module.addTypeAliases(typeAliases);
     * }</pre></p>
     * <p>This example adds two type aliases {@code person} and {@code employee} that map to the classes</p>
     * @param typeAliases the type aliases to add
     * @return this module
     */
    public VMFJsonSchemaGenerator withTypeAliases(Map<String, String> typeAliases) {
        typeAliases.forEach(this::addTypeAlias);
        return this;
    }

    /**
     * Create a new instance of VMFJacksonModule.
     * @return a new instance of VMFJacksonModule
     */
    public static VMFJsonSchemaGenerator newInstance(VMFJacksonModule.RUNTIME_TYPE runtimeType) {
        if(!VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL.equals(runtimeType)) {
            throw new RuntimeException("Please confirm that you are using an experimental piece of software by calling" +
                    " 'newInstance(RUNTIME_TYPE.EXPERIMENTAL)'.");
        }
        var module = new VMFJsonSchemaGenerator();
        return module;
    }

    /**
     * Create a new instance of VMFJacksonModule.
     * @return a new instance of VMFJacksonModule
     */
    public static VMFJsonSchemaGenerator newInstance() {
        throw new RuntimeException("Please confirm that you are using an experimental piece of software by calling" +
                " 'newInstance(RUNTIME_TYPE.EXPERIMENTAL)'.");

//        var module = new VMFJacksonModule();
//        return module;
    }


    /**
     * Generate a JSON schema for the specified model class. The schema is generated based on the model class and its
     * properties. The schema is generated in draft-07 format. This method is used internally to generate the schema.
     * @param modelClass the model class for which to generate the schema
     * @return the generated schema as a map
     */
    private Map<String, Object> _generateSchema(Class<? extends VObject> modelClass) {
        Map<String, Object> schema = new HashMap<>();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("title", modelClass.getSimpleName());
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();
        schema.put("properties", properties);

        Type type = VMFTypeUtils.forClass(modelClass);

        VObject prop = createPrototypeIfPossible(modelClass);

        for (Property property : prop!=null?prop.vmf().reflect().properties():type.reflect().properties()) {
            if (!isToBeExcludedFromSerialization(property)) {
                properties.put(getFieldNameForProperty(property), getPropertySchema(property));
            }
        }

        schema.put("definitions", generateDefinitionsForModelRoot(type));

        return schema;
    }

    /**
     * Create an intermediate instance of the model class to get the default values.
     * @param type the type to create the prototype for
     * @return the prototype instance
     */
    private static VObject createPrototypeIfPossible(Type type) {
        Class modelClass = null;
        try {
            modelClass = Class.forName(type.getName());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return createPrototypeIfPossible(modelClass);
    }

    /**
     * Create an intermediate instance of the model class to get the default values.
     * @param modelClass the model class to create the prototype for
     * @return the prototype instance
     */
    private static VObject createPrototypeIfPossible(Class<? extends VObject> modelClass) {
        // create an intermediate instance of the model class to get the default values
        VObject prop = null;
        try {
            // Get the builder class and instance
            Class<?> builderClass = getBuilderClass(modelClass);
            Builder builder = (Builder) builderClass.getDeclaredMethod("newInstance").invoke(null);
            prop = builder.build();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return prop;
    }

    /**
     * Generate a JSON schema for the given property.
     * @param property the property to generate the schema for
     * @return the generated schema
     */
    private Map<String, Object> getPropertySchema(Property property) {
        Map<String, Object> propertySchema = new HashMap<>();

        if (isToBeExcludedFromSerialization(property)) {
            return propertySchema;
        } else if (isValueType(property.getType())) {
            propertySchema.put("type", mapValueType(property.getType()));
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (property.getType().isModelType() && !property.getType().isListType()) {
            // Handle polymorphic types
            Type elementType = VMFTypeUtils.forClass(property.getType().getName());
            if (!VMFTypeUtils.getSubTypes(elementType).isEmpty()) {

                var typesToChooseFrom = VMFTypeUtils.getSubTypes(elementType);
                typesToChooseFrom.add(elementType);
                typesToChooseFrom.removeIf(Type::isInterfaceOnly);

                propertySchema.put("oneOf", typesToChooseFrom.stream().map(subType -> {
                    Map<String, Object> typeSchema = new HashMap<>();

                    // check type aliases
                    var typeAlias = getTypeAlias(subType);

                    typeSchema.put("$ref", "#/definitions/" + typeAlias);

                    // if subType equals the element type, we don't need to add the @vmf-type property
//                    if (!subType.equals(elementType))
                    {
                        typeSchema.put("properties", Map.of("@vmf-type",
                                Map.of("type", "string", "enum",
                                        List.of(typeAlias), "readOnly", true))
                        );
                        typeSchema.put("required", new String[]{"@vmf-type"});
                    }
                    return typeSchema;
                }).toArray());
            } else {
                var typeAlias = getTypeAlias(property.getType());
                propertySchema.put("$ref", "#/definitions/" + typeAlias);
            }
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (property.getType().isListType()) {
            propertySchema.put("type", "array");
            Map<String, Object> itemsSchema = new HashMap<>();

            // Handle polymorphic types with oneOf and add @vmf-type as a required property
            Type elementType = VMFTypeUtils.forClass(property.getType().getElementTypeName().get());
            if (isValueType(elementType)) {
                itemsSchema.put("type", mapValueType(elementType));
            } else
            if (!VMFTypeUtils.getSubTypes(elementType).isEmpty()) {

                var typesToChooseFrom = VMFTypeUtils.getSubTypes(elementType);
                typesToChooseFrom.add(elementType);
                typesToChooseFrom.removeIf(Type::isInterfaceOnly);

                itemsSchema.put("oneOf", typesToChooseFrom.stream().map(subType -> {

                    var typeAlias = getTypeAlias(subType);

                    Map<String, Object> typeSchema = new HashMap<>();
                    typeSchema.put("$ref", "#/definitions/" + typeAlias);

                    // if subType equals the element type, we don't need to add the @vmf-type property
//                    if (!subType.equals(elementType))
                    {
                        typeSchema.put("properties", Map.of("@vmf-type",
                                Map.of("type", "string", "enum",
                                        List.of(typeAlias), "readOnly", true))
                        );
                        typeSchema.put("required", new String[]{"@vmf-type"});
                    }
                    return typeSchema;
                }).toArray());
            } else {
                var typeAlias = getTypeAlias(elementType);
                itemsSchema.put("$ref", "#/definitions/" + typeAlias);
            }
            propertySchema.put("items", itemsSchema);
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (VMFTypeUtils.isEnum(property.getType())) {
            propertySchema.put("type", "string");
            propertySchema.put("enum", VMFTypeUtils.getEnumConstants(property.getType()));
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else {
            propertySchema.put("type", "string");
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        }

        return propertySchema;
    }


    /**
     * Generate definitions for the given type.
     * @param type the type to generate the definitions for
     * @return the generated definitions
     */
    private Map<String, Object> generateDefinitionsForModelRoot(Type type) {
        Map<String, Object> definitions = new HashMap<>();
        for (Type subType : type.reflect().allTypes()) {
            if (subType.isInterfaceOnly()) continue;

            Map<String, Object> definition = new HashMap<>();
            definition.put("type", "object");

            VObject propParent = createPrototypeIfPossible(subType);

            Map<String, Object> properties = new HashMap<>();
            for (Property property : propParent!=null?propParent.vmf().reflect().properties() : subType.reflect().properties()) {
                if (!isToBeExcludedFromSerialization(property)) {
                    properties.put(getFieldNameForProperty(property), getPropertySchema(property));
                }
            }

//            if (!subType.superTypes().isEmpty()) {
//                definition.put("allOf", subType.superTypes().stream().map(superType -> {
//
//                    var typeAlias = getTypeAlias(superType);
//
//                    Map<String, Object> ref = new HashMap<>();
//                    ref.put("$ref", "#/definitions/" + typeAlias);
//                    return ref;
//                }).toArray());
//            }

            var typeAlias = getTypeAlias(subType);

            definition.put("properties", properties);
            definitions.put(typeAlias, definition);
        }
        return definitions;
    }

    /**
     * Check if the given property should be excluded from serialization.
     * @param property the property to check
     * @return {@code true} if the given property should be excluded from serialization, {@code false} otherwise
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

        return property.getType().isModelType() && !immutableType && !VMFTypeUtils.isParentOfPropContainer(property);
    }

    /**
     * Map a value type to a JSON schema type.
     * @param type the type to map
     * @return the JSON schema type
     */
    private static String mapValueType(Type type) {
        if (isInteger(type)) return "integer";
        if (isBoolean(type)) return "boolean";
        if (isDouble(type) || isFloat(type)) return "number";
        return "string";
    }

    /**
     * Check if the given type is an integer (or short or long).
     * @param type the type to check
     * @return {@code true} if the given type is an integer, {@code false} otherwise
     */
    public static boolean isInteger(Type type) {
        return type.getName().equals("java.lang.Integer") || type.getName().equals("int") ||
                type.getName().equals("java.lang.Short") || type.getName().equals("short") ||
                type.getName().equals("java.lang.Long") || type.getName().equals("long");
    }

    /**
     * Check if the given type is a boolean.
     * @param type the type to check
     * @return {@code true} if the given type is a boolean, {@code false} otherwise
     */
    public static boolean isBoolean(Type type) {
        return type.getName().equals("java.lang.Boolean") || type.getName().equals("boolean");
    }

    /**
     * Check if the given type is a float.
     * @param type the type to check
     * @return {@code true} if the given type is a float, {@code false} otherwise
     */
    public static boolean isFloat(Type type) {
        return type.getName().equals("java.lang.Float") || type.getName().equals("float");
    }

    /**
     * Check if the given type is a double.
     * @param type the type to check
     * @return {@code true} if the given type is a double, {@code false} otherwise
     */
    public static boolean isDouble(Type type) {
        return type.getName().equals("java.lang.Double") || type.getName().equals("double");
    }

    /**
     * Check if the given type is a value type.
     * @param type the type to check
     * @return {@code true} if the given type is a value type, {@code false} otherwise
     */
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

    private String getTypeAlias(Type type) {
        return typeAliasesReverse.getOrDefault(type.getName(), type.getName());
    }

    private void addDefaultValueAndDescriptionAndConstraintIfAvailable(Property property, Map<String, Object> propertySchema) {
        addDefaultValue(property, propertySchema);
        addDescription(property, propertySchema);
        addConstraint(property, propertySchema);
        addFormat(property, propertySchema);
        addUniqueItems(property, propertySchema);
        addInjections(property, propertySchema);
        addTitle(property, propertySchema);
        addPropertyOrder(property, propertySchema);
    }

    private void addDefaultValue(Property property, Map<String, Object> propertySchema) {
        try {
            Object defaultValue = property.getDefault();
            if (defaultValue != null) {
                propertySchema.put("default", defaultValue);
            }
        } catch (Exception e) {
            // ignore, not possible to get default value
        }
    }

    private void addDescription(Property property, Map<String, Object> propertySchema) {
        try {
            var description = property.annotationByKey("vmf:jackson:schema:description").get().getValue();
            if (description != null) {
                propertySchema.put("description", description);
            }
        } catch (Exception e) {
            // ignore, not possible to get default value
        }
    }

    private void addConstraint(Property property, Map<String, Object> propertySchema) {
        try {
            var constraints = property.annotations().stream()
                    .filter(a -> a.getKey().equals("vmf:jackson:schema:constraint"))
                    .map(Annotation::getValue).collect(Collectors.toList());


            for(String constraint : constraints) {
                // split constraint into key-value pairs "pattern=^\\d{3}$"
                // or "minimum=0" or "maximum=99"

                if (constraint == null || constraint.isBlank() || !constraint.contains("=")) {
                    return;
                }

                // split at first occurrence of "=", so that we can have values with "=" in them
                var constraintSplit = constraint.split("=", 2);

                var constraintName  = constraintSplit[0];
                var constraintValue = constraintSplit[1];

                if (constraintName != null && constraintValue != null
                        && !constraintName.isBlank() && !constraintValue.isBlank()) {

                    // convert constraint value to boolean, integer or double if possible
                    Object constraintValueToWrite = constraintValue;
                    try {
                        constraintValueToWrite = Integer.parseInt(constraintValue);
                    } catch (NumberFormatException e) {
                        try {
                            constraintValueToWrite = Double.parseDouble(constraintValue);
                        } catch (NumberFormatException e2) {
                            try {
                                if(constraintValue.equalsIgnoreCase("true")
                                        || constraintValue.equalsIgnoreCase("false")) {
                                    constraintValueToWrite = Boolean.parseBoolean(constraintValue);
                                }
                            } catch (NumberFormatException e3) {
                                // ignore, not possible to convert to boolean, integer or double, we assume string
                            }
                        }
                    }

                    propertySchema.put(constraintName, constraintValueToWrite);
                }
            }
        } catch (Exception e) {
            // ignore, not possible to get default value
        }
    }

    private void addUniqueItems(Property property, Map<String, Object> propertySchema) {
        try {
            var uniqueItemsOpt = property.annotationByKey("vmf:jackson:schema:uniqueItems");
            if (uniqueItemsOpt.isPresent() && uniqueItemsOpt.get().getValue() != null) {
                var uniqueItems = uniqueItemsOpt.get().getValue();
                if (uniqueItems != null) {
                    propertySchema.put("uniqueItems", Boolean.parseBoolean(uniqueItems));
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse uniqueItems", e);
        }
    }

    private void addInjections(Property property, Map<String, Object> propertySchema) {
        try {
            var injectionsOpt = property.annotationByKey("vmf:jackson:schema:inject");
            if (injectionsOpt.isPresent() && injectionsOpt.get().getValue() != null) {
                var injections = injectionsOpt.get().getValue();
                if (injections != null) {
                    // inject json into schema by parsing it and adding it to the schema
                    var injectionsMap = new ObjectMapper().readValue("{" + injections + "}", Map.class);
                    propertySchema.putAll(injectionsMap);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse injection JSON", e);
        }
    }

    private void addFormat(Property property, Map<String, Object> propertySchema) {
        try {
            var format = property.annotationByKey("vmf:jackson:schema:format").get().getValue();
            if (format != null) {
                propertySchema.put("format", format);
            }
        } catch (Exception e) {
            // ignore, not possible to get default value
        }
    }

    private void addTitle(Property property, Map<String, Object> propertySchema) {
        try {
            var title = property.annotationByKey("vmf:jackson:schema:title").get().getValue();
            if (title != null) {
                propertySchema.put("title", title);
            }
        } catch (Exception e) {
            // ignore, not possible to get default value
        }
    }

    private void addPropertyOrder(Property property, Map<String, Object> propertySchema) {
        // order is an integer value that specifies the order of the property in the schema
        try {
            var orderOpt = property.annotationByKey("vmf:jackson:schema:propertyOrder");
            if (orderOpt.isPresent() && orderOpt.get().getValue() != null) {
                var order = orderOpt.get().getValue();
                if (order != null) {
                    propertySchema.put("propertyOrder", Integer.parseInt(order));
                }
            } else{
                // TODO reuse property order from vmf (traversal order via @PropertyOrder, vmf should report th order
                //  as @Annotation("key="...", value = "...") as well
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse property order", e);
        }
    }
}
