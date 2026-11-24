package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import eu.mihosoft.vmf.runtime.core.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static eu.mihosoft.vmf.jackson.VMFTypeUtils.getBuilderClass;

/**
 * A JSON schema generator for VMF models. This class generates a draft-07 JSON schema for a VMF
 * model class based on the model's types and properties.
 *
 * <p>The generator is experimental and cannot be instantiated directly: create it via the
 * {@link #newInstance(VMFJacksonModule.RUNTIME_TYPE)} factory method, passing
 * {@link VMFJacksonModule.RUNTIME_TYPE#EXPERIMENTAL}.</p>
 *
 * <p>Example usage:
 * <pre>{@code
 * String schema = VMFJsonSchemaGenerator.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
 *          .withTypeAlias("person", Person.class.getName())
 *          .withTypeAlias("employee", Employee.class.getName())
 *          .generateSchemaAsString(MyModel.class);
 * }</pre>
 * <p>Type aliases control the names used for the type definitions and {@code @vmf-type}
 * discriminators in the generated schema.</p>
 */
public class VMFJsonSchemaGenerator {
    public enum RUNTIME_TYPE {
        RELEASE, EXPERIMENTAL
    }

    private final Map<String, String> typeAliases        = new HashMap<>();
    private final Map<String, String> typeAliasesReverse = new HashMap<>();
    private boolean referencesEnabled;

    /**
     * Includes the native id/reference wire fields used by
     * {@link VMFJacksonModule#withReferences(boolean)}. Disabled by default.
     * Id uniqueness and resolution are checked by the native reader, which
     * restores opposite relationships. JSON Schema does not express those
     * graph constraints or validate the consistency of supplied opposites.
     * @param enabled whether to describe reference-enabled serialization
     * @return this generator
     */
    public VMFJsonSchemaGenerator withReferences(boolean enabled) {
        referencesEnabled = enabled;
        return this;
    }

    /**
     * @return whether native id/reference wire fields are included in the schema
     */
    public boolean isReferencesEnabled() { return referencesEnabled; }

    /** One generation run uses the model's own declarations and class loader. */
    private static final class SchemaTypes {
        private final ClassLoader loader;
        private final List<Type> declarations;

        SchemaTypes(ClassLoader loader, List<Type> declarations) {
            this.loader = loader;
            this.declarations = declarations;
        }

        Class<?> load(String name) {
            try {
                return Class.forName(name, false, loader);
            } catch (ClassNotFoundException ex) {
                throw new IllegalArgumentException("Cannot resolve model property type " + name, ex);
            }
        }

        List<Type> concreteSubtypes(Type parent) {
            return declarations.stream().filter(t -> !t.isInterfaceOnly() && t.isModelSubtypeOf(parent))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Adds a type alias to this generator. A type alias maps a short type name to a fully qualified
     * class name and controls the name used for that type's definition and {@code @vmf-type}
     * discriminator in the generated schema.
     *
     * <p>Example:
     * <pre>{@code
     * generator.addTypeAlias("person", Person.class.getName());
     * }</pre>
     * <p>This adds the type alias {@code person} that maps to the {@code Person} class.
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
     * Creates an {@link ObjectMapper} preconfigured for reading and writing the schema map
     * returned by {@link #generateSchema(Class)}. The mapper preserves the insertion order of the
     * schema (which encodes property/declaration order via {@code propertyOrder}) instead of
     * sorting map entries alphabetically, so callers that turn the schema map into a string or a
     * {@code JsonNode} get a stable, order-preserving result.
     *
     * @return a new, preconfigured object mapper for processing the schema map
     */
    public static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // the schema is built from LinkedHashMaps and carries an explicit "propertyOrder"; keep
        // that order rather than re-sorting map entries alphabetically.
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, false);
        return mapper;
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
            return createObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Adds a type alias to this generator and returns the generator for chaining. See
     * {@link #addTypeAlias(String, String)}.
     *
     * <p>Example:
     * <pre>{@code
     * generator.withTypeAlias("person", Person.class.getName())
     *          .withTypeAlias("employee", Employee.class.getName());
     * }</pre>
     */
    public VMFJsonSchemaGenerator withTypeAlias(String alias, String className) {
        addTypeAlias(alias, className);
        return this;
    }

    /**
     * Adds multiple type aliases to this generator at once and returns the generator for chaining.
     * <p>Example:
     * <pre>{@code
     * Map<String, String> typeAliases = new HashMap<>();
     * typeAliases.put("person", Person.class.getName());
     * typeAliases.put("employee", Employee.class.getName());
     * generator.withTypeAliases(typeAliases);
     * }</pre></p>
     * @param aliases the type aliases to add
     * @return this generator
     */
    public VMFJsonSchemaGenerator withTypeAliases(Map<String, String> aliases) {
        if (aliases != null) {
            aliases.forEach(this::addTypeAlias);
        }
        return this;
    }

    /**
     * Creates a new experimental instance of the schema generator.
     * @param runtimeType must be {@link VMFJacksonModule.RUNTIME_TYPE#EXPERIMENTAL} to acknowledge
     *                    that this is experimental software
     * @return a new schema generator instance
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
     * Always throws: the generator is experimental and must be created via
     * {@link #newInstance(VMFJacksonModule.RUNTIME_TYPE)} with
     * {@link VMFJacksonModule.RUNTIME_TYPE#EXPERIMENTAL}.
     * @return never returns normally
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
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("title", modelClass.getSimpleName());
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        schema.put("properties", properties);
        if (referencesEnabled) properties.put(VMFReferences.ID_FIELD, idSchema(false));

        Type type = VMFTypeUtils.forClass(modelClass);

        // capture the full type list up-front so polymorphic properties can resolve their
        // concrete subtypes even when the declared element type is @InterfaceOnly.
        SchemaTypes types = new SchemaTypes(modelClass.getClassLoader(), type.reflect().allTypes());

        VObject prop = createPrototypeIfPossible(modelClass);

        int order = 0;
        for (Property property : prop!=null?prop.vmf().reflect().properties():type.reflect().properties()) {
            if (!isToBeExcludedFromSerialization(property, types)) {
                Map<String, Object> propertySchema = getPropertySchema(property, types);
                // preserve VMF property/traversal order in the generated schema so editors
                // (and stable test output) render fields in the order they are declared.
                propertySchema.putIfAbsent("propertyOrder", order);
                properties.put(getFieldNameForProperty(property), propertySchema);
                order++;
            }
        }

        schema.put("definitions", generateDefinitionsForModelRoot(types));

        return schema;
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
    private Map<String, Object> getPropertySchema(Property property, SchemaTypes types) {
        Map<String, Object> propertySchema = new LinkedHashMap<>();

        if (isToBeExcludedFromSerialization(property, types)) {
            return propertySchema;
        } else if (referencesEnabled && isIdReference(property, types)) {
            Type elementType = VMFTypeUtils.forClass(types.load(property.getType().getElementTypeName().orElse(property.getType().getName())));
            Map<String, Object> referenceSchema = property.getType().isReferenceSetType() ? idSchema(false) : referenceValueSchema(elementType, types);
            if (property.getType().isListType() || property.getType().isReferenceSetType()) {
                propertySchema.put("type", "array");
                propertySchema.put("items", referenceSchema);
            } else {
                propertySchema.putAll(referenceSchema);
            }
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (property.getType().isMapType()) {
            var args = property.getType().getDescriptor().getArguments();
            String key = args.get(0).getName();
            String value = args.get(1).getName();
            if (String.class.getName().equals(key)) {
                propertySchema.put("type", "object");
                propertySchema.put("additionalProperties", scalarItemSchema(value, true));
            } else {
                propertySchema.put("type", "array");
                propertySchema.put("items", Map.of("type", "object", "additionalProperties", false,
                        "required", List.of("key", "value"),
                        "properties", Map.of("key", scalarItemSchema(key, false), "value", scalarItemSchema(value, true))));
            }
            var bounds = property.getMultiplicity();
            boolean object = String.class.getName().equals(key);
            if (bounds.getLower() > 0) propertySchema.put(object ? "minProperties" : "minItems", bounds.getLower());
            if (bounds.getUpper() >= 0) propertySchema.put(object ? "maxProperties" : "maxItems", bounds.getUpper());
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (property.getType().isSetType()) {
            String element = property.getType().getDescriptor().getArguments().get(0).getName();
            propertySchema.put("type", "array");
            propertySchema.put("items", scalarItemSchema(element, false));
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (isValueType(property.getType())) {
            propertySchema.put("type", mapValueType(property.getType()));
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (property.getType().isModelType() && !property.getType().isListType() && !property.getType().isReferenceSetType()) {
            // Handle polymorphic types
            Type elementType = property.getType();

            var typesToChooseFrom = types.concreteSubtypes(elementType);
            if (!typesToChooseFrom.isEmpty()) {
                typesToChooseFrom.add(elementType);
                typesToChooseFrom.removeIf(Type::isInterfaceOnly);

                propertySchema.put("oneOf", typesToChooseFrom.stream()
                        .map(subType -> polymorphicOption(getTypeAlias(subType))).toArray());
            } else {
                var typeAlias = getTypeAlias(property.getType());
                propertySchema.put("$ref", "#/definitions/" + typeAlias);
            }
            addDefaultValueAndDescriptionAndConstraintIfAvailable(property, propertySchema);
        } else if (property.getType().isListType() || property.getType().isReferenceSetType()) {
            propertySchema.put("type", "array");
            Map<String, Object> itemsSchema = new LinkedHashMap<>();

            // Handle polymorphic types with oneOf and add @vmf-type as a required property
            Type elementType = VMFTypeUtils.forClass(types.load(property.getType().getElementTypeName().get()));

            var typesToChooseFrom = types.concreteSubtypes(elementType);
            if (isValueType(elementType)) {
                itemsSchema.put("type", mapValueType(elementType));
            } else if (!typesToChooseFrom.isEmpty()) {
                typesToChooseFrom.add(elementType);
                typesToChooseFrom.removeIf(Type::isInterfaceOnly);

                itemsSchema.put("oneOf", typesToChooseFrom.stream()
                        .map(subType -> polymorphicOption(getTypeAlias(subType))).toArray());
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

        if (property.getType().isSetType() || property.getType().isListType() || property.getType().isReferenceSetType()) {
            var multiplicity = property.getMultiplicity();
            if (multiplicity.getLower() > 0) propertySchema.put("minItems", multiplicity.getLower());
            if (multiplicity.getUpper() >= 0) propertySchema.put("maxItems", multiplicity.getUpper());
            String element = property.getType().getDescriptor().getArguments().get(0).getName();
            // JSON Schema equates signed zeroes and structurally equal objects. Java model identity and
            // Float/Double membership can distinguish them, so the runtime enforces uniqueness in these cases.
            if (property.getType().isReferenceSetType() && referencesEnabled && isIdReference(property, types)) {
                propertySchema.put("uniqueItems", true);
            } else if (multiplicity.isUnique() && !property.getType().isModelType()
                    && !Float.class.getName().equals(element) && !Double.class.getName().equals(element)) {
                propertySchema.put("uniqueItems", true);
            }
            property.getKeyPropertyName().ifPresent(keyName -> {
                // Requiring the key is specific to entries in this list. The entry type may be used independently.
                Type entryType = VMFTypeUtils.forClass(types.load(element));
                Property key = entryType.reflect().propertyByName(keyName).orElseThrow();
                String field = getFieldNameForProperty(key);
                Map<String, Object> requiredKey = Map.of("required", List.of(field), "properties",
                        Map.of(field, Map.of("not", Map.of("type", "null"))));
                propertySchema.put("items", Map.of("allOf", List.of(propertySchema.get("items"), requiredKey)));
            });
        }
        return propertySchema;
    }


    private static Map<String, Object> scalarItemSchema(String valueName, boolean nullable) {
        Type type = VMFTypeUtils.forClass(valueName);
        if (isDouble(type) || isFloat(type)) {
            // Jackson encodes non-finite numbers as strings under its default settings.
            return Map.of("anyOf", List.of(Map.of("type", nullable ? List.of("number", "null") : "number"),
                    Map.of("enum", List.of("NaN", "Infinity", "-Infinity"))));
        }
        if (valueName.equals("java.lang.Character")) {
            return Map.of("type", nullable ? List.of("string", "null") : "string", "minLength", 1, "maxLength", 1);
        }
        String scalar = valueName.equals("java.lang.Byte") ? "integer" : mapValueType(type);
        return Map.of("type", nullable ? List.of(scalar, "null") : scalar);
    }

    /**
     * Generate definitions for the given type.
     * @param types types declared by this model
     * @return the generated definitions
     */
    private Map<String, Object> generateDefinitionsForModelRoot(SchemaTypes types) {
        Map<String, Object> definitions = new LinkedHashMap<>();
        for (Type subType : types.declarations) {
            if (subType.isInterfaceOnly()) continue;

            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("type", "object");

            VObject propParent = createPrototypeIfPossible(types.load(subType.getName()).asSubclass(VObject.class));

            Map<String, Object> properties = new LinkedHashMap<>();
            if (referencesEnabled) properties.put(VMFReferences.ID_FIELD, idSchema(false));
            int order = 0;
            for (Property property : propParent!=null?propParent.vmf().reflect().properties() : subType.reflect().properties()) {
                if (!isToBeExcludedFromSerialization(property, types)) {
                    Map<String, Object> propertySchema = getPropertySchema(property, types);
                    propertySchema.putIfAbsent("propertyOrder", order);
                    properties.put(getFieldNameForProperty(property), propertySchema);
                    order++;
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
    private boolean isToBeExcludedFromSerialization(Property property, SchemaTypes types) {
        if (!property.getType().isModelType()) return false;
        if (property.getType().isReferenceSetType() && VMFReferences.isReferenceProperty(property)) return !referencesEnabled;
        String valueType = property.getType().getElementTypeName().orElse(property.getType().getName());
        boolean immutableType = Immutable.class.isAssignableFrom(types.load(valueType));
        return property.getType().isModelType() && !immutableType && !VMFTypeUtils.isParentOfPropContainer(property)
                && !(referencesEnabled && VMFReferences.isReferenceProperty(property));
    }

    private static boolean isIdReference(Property property, SchemaTypes types) {
        if (!VMFReferences.isReferenceProperty(property)) return false;
        if (property.getType().isReferenceSetType()) return true;
        String name = property.getType().isListType()
                ? property.getType().getElementTypeName().get() : property.getType().getName();
        return !Immutable.class.isAssignableFrom(types.load(name));
    }

    /** Runtime immutable implementations of an interface-only reference type
     * are written by value, while mutable implementations are written as ids. */
    private Map<String, Object> referenceValueSchema(Type declaredType, SchemaTypes types) {
        List<Map<String, Object>> immutableOptions = types.concreteSubtypes(declaredType).stream()
                .filter(t -> Immutable.class.isAssignableFrom(types.load(t.getName())))
                .map(t -> polymorphicOption(getTypeAlias(t)))
                .collect(Collectors.toList());
        if (immutableOptions.isEmpty()) return idSchema(true);

        List<Map<String, Object>> options = new ArrayList<>();
        options.add(idSchema(true));
        options.addAll(immutableOptions);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("oneOf", options);
        return schema;
    }

    private static Map<String, Object> idSchema(boolean nullable) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", nullable ? new String[]{"string", "integer", "null"} : new String[]{"string", "integer"});
        return schema;
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

    /**
     * Builds a single {@code oneOf} option for a polymorphic property: a reference to the
     * subtype definition combined via {@code allOf} with a required {@code @vmf-type}
     * discriminator fixed to the subtype's alias.
     *
     * <p>The discriminator must be combined via {@code allOf} rather than placed as a sibling
     * of {@code $ref}: under JSON Schema draft-07 any keyword next to {@code $ref} is ignored,
     * so a sibling discriminator would be silently dropped by validators. This is what
     * previously made "options about extending types" not work in the generated schema.</p>
     *
     * @param typeAlias the alias (or fully qualified name) of the subtype
     * @return the schema fragment describing this polymorphic choice
     */
    private Map<String, Object> polymorphicOption(String typeAlias) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("$ref", "#/definitions/" + typeAlias);

        Map<String, Object> discriminator = new LinkedHashMap<>();
        discriminator.put("properties", Map.of("@vmf-type",
                Map.of("type", "string", "enum", List.of(typeAlias), "readOnly", true)));
        discriminator.put("required", new String[]{"@vmf-type"});

        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", typeAlias);
        option.put("allOf", new Object[]{ref, discriminator});
        return option;
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
            if (defaultValue instanceof Map<?, ?> && property.getType().isMapType()
                    && !String.class.getName().equals(property.getType().getDescriptor().getArguments().get(0).getName())) {
                var entries = new ArrayList<Map<String, Object>>();
                ((Map<?, ?>) defaultValue).forEach((key, value) -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("key", key);
                    entry.put("value", value);
                    entries.add(entry);
                });
                propertySchema.put("default", entries);
            } else if (defaultValue != null) {
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
