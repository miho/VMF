package eu.mihosoft.vmf.jackson;

import eu.mihosoft.vmf.runtime.core.*;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static eu.mihosoft.vmf.jackson.VMFTypeUtils.getBuilderClass;

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

    public Map<String, Object> generateSchema(Class<? extends VObject> modelClass) {
        return _generateSchema(modelClass);
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

        schema.put("definitions", generateDefinitions(type));

        return schema;
    }

    private static VObject createPrototypeIfPossible(Type type) {
        Class modelClass = null;
        try {
            modelClass = Class.forName(type.getName());
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return createPrototypeIfPossible(modelClass);
    }

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

    private Map<String, Object> getPropertySchema(Property property) {
        Map<String, Object> propertySchema = new HashMap<>();

        if (isToBeExcludedFromSerialization(property)) {
            return propertySchema;
        } else if (isValueType(property.getType())) {
            propertySchema.put("type", mapValueType(property.getType()));
            addDefaultValue(property, propertySchema);
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
                    typeSchema.put("properties", Map.of("@vmf-type",
                            Map.of("type", "string", "enum",
                                    List.of(typeAlias), "readOnly", true))
                    );
                    typeSchema.put("required", new String[]{"@vmf-type"});
                    return typeSchema;
                }).toArray());
            } else {
                var typeAlias = getTypeAlias(property.getType());
                propertySchema.put("$ref", "#/definitions/" + typeAlias);
            }
            addDefaultValue(property, propertySchema);
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

                    var typeAlias = getTypeAlias(subType);

                    Map<String, Object> typeSchema = new HashMap<>();
                    typeSchema.put("$ref", "#/definitions/" + typeAlias);
                    typeSchema.put("properties", Map.of("@vmf-type",
                            Map.of("type", "string", "enum",
                                    List.of(typeAlias), "readOnly", true))
                    );
                    typeSchema.put("required", new String[]{"@vmf-type"});
                    return typeSchema;
                }).toArray());
            } else {
                var typeAlias = getTypeAlias(elementType);
                itemsSchema.put("$ref", "#/definitions/" + typeAlias);
            }

            propertySchema.put("items", itemsSchema);
            addDefaultValue(property, propertySchema);
        } else if (VMFTypeUtils.isEnum(property.getType())) {
            propertySchema.put("type", "string");
            propertySchema.put("enum", VMFTypeUtils.getEnumConstants(property.getType()));
            addDefaultValue(property, propertySchema);
        } else {
            propertySchema.put("type", "string");
            addDefaultValue(property, propertySchema);
        }

        return propertySchema;
    }


    private Map<String, Object> generateDefinitions(Type type) {
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

            if (!subType.superTypes().isEmpty()) {
                definition.put("allOf", subType.superTypes().stream().map(superType -> {

                    var typeAlias = getTypeAlias(superType);

                    Map<String, Object> ref = new HashMap<>();
                    ref.put("$ref", "#/definitions/" + typeAlias);
                    return ref;
                }).toArray());
            }

            var typeAlias = getTypeAlias(subType);

            definition.put("properties", properties);
            definitions.put(typeAlias, definition);
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

    private String getTypeAlias(Type type) {
        return typeAliasesReverse.getOrDefault(type.getName(), type.getName());
    }

    private void addDefaultValue(Property property, Map<String, Object> propertySchema) {
        try {
            Object defaultValue = property.getDefault();
            if (defaultValue != null) {
                propertySchema.put("default", defaultValue);
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
