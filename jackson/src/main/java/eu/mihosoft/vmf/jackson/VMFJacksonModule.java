package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Reflect;
import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;
import eu.mihosoft.vmf.runtime.core.VObject;

import javax.xml.namespace.QName;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

/**
 * Generic VMF Jackson module. This module provides a serializer and deserializer for VMF models. It supports polymorphism
 * by looking for a special field {@code @vmf-type} or {@code <vmf-type>} in case of XML that specifies the actual type
 * of the object.
 *
 * <p>Example usage:
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper();
 *
 * mapper.registerModule(VMFJacksonModule.newInstance(VMFJacksonModule.RUNTIME_TYPE.EXPERIMENTAL)
 *          .withTypeAlias("person", Person.class.getName())
 *          .withTypeAlias("employee", Employee.class.getName())
 *       );
 * }</pre>
 * </p>
 * <p>The module is experimental and cannot be instantiated directly: create it via the
 * {@link #newInstance(RUNTIME_TYPE)} factory method, passing {@link RUNTIME_TYPE#EXPERIMENTAL}.
 * This example registers the module with the type aliases {@code person} and {@code employee}
 * that map to the classes {@code Person} and {@code Employee} respectively.</p>
 *
 * <p><b>Containment and references.</b> By default the module writes the containment tree of a model:
 * contained objects, external values and immutable values. Non-containment references
 * ({@code @Refers} properties and other model-typed properties that are neither contained nor a
 * container) are not written. With {@link #withReferences(boolean) withReferences(true)} they are
 * written as ids and restored by identity, see {@link #withReferences(boolean)}.</p>
 *
 * <p>For durable edit history, capture a journal with
 * {@link eu.mihosoft.vmf.runtime.core.journal.JournalRecorder} and persist it with
 * {@link VMFJournalCodec}. The module rejects serialization and deserialization of live
 * {@link Change} events.</p>
 *
 */
public class VMFJacksonModule extends SimpleModule {

    private static final String LIVE_CHANGE_MESSAGE = "VMF Change events represent live model edits. "
            + "Capture a ChangeJournal with JournalRecorder and use VMFJournalCodec to write or read it.";

    private static final JsonSerializer<Change> LIVE_CHANGE_SERIALIZER = new StdSerializer<Change>(Change.class) {
        @Override
        public void serialize(Change value, JsonGenerator gen, SerializerProvider provider) throws IOException {
            throw JsonMappingException.from(gen, LIVE_CHANGE_MESSAGE);
        }

        @Override
        public void serializeWithType(Change value, JsonGenerator gen, SerializerProvider provider,
                                      TypeSerializer typeSerializer) throws IOException {
            serialize(value, gen, provider);
        }
    };

    private static final JsonDeserializer<Change> LIVE_CHANGE_DESERIALIZER = new StdDeserializer<Change>(Change.class) {
        @Override
        public Change deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            throw JsonMappingException.from(parser, LIVE_CHANGE_MESSAGE);
        }
    };

    public enum RUNTIME_TYPE {
        RELEASE, EXPERIMENTAL
    }

    private final Map<String, String> typeAliases = new HashMap<>();
    private final Map<String, String> typeAliasesReverse = new HashMap<>();

    private volatile boolean referencesEnabled;
    private volatile ModelRegistry modelRegistry;

    VMFJacksonModule withModelRegistry(ModelRegistry registry) {
        this.modelRegistry = Objects.requireNonNull(registry, "registry");
        return this;
    }

    /**
     * Enables or disables non-containment references (disabled by default). If enabled, the module
     * also writes the properties that refer to model objects without containing them: properties
     * annotated with {@code @Refers} (with opposite) and other model-typed properties that are
     * neither contained nor a container. Reading restores them by identity: a reference points to
     * the same restored instance that appears in the containment tree, and the opposites of
     * {@code @Refers} properties are restored as well.
     *
     * <p>Representation: every object of the written containment tree that is the target of a
     * reference gets an id in the field {@code @vmf-id} ({@code vmf-id} in XML), and a reference is
     * written as that id (a list of ids for list properties). Ids are assigned in containment order
     * starting at {@code "1"}; when reading, any string or integer is accepted as an id. Immutable
     * values are written by value as before.</p>
     *
     * <p>Example: the document contains the item ({@code items}) and selects it with the
     * {@code @Refers} property {@code selectedItem}, whose opposite {@code selectedBy} refers back to
     * the document, so both objects are referenced and get an id:</p>
     * <pre>{@code
     * {
     *   "@vmf-id" : "1",
     *   "id" : "document-1",
     *   "items" : [ {
     *     "@vmf-id" : "2",
     *     "id" : "item-1",
     *     "selectedBy" : "1"
     *   } ],
     *   "selectedItem" : "2"
     * }
     * }</pre>
     *
     * <p>References are resolved within the outermost VMF object that is written or read: every
     * referenced object must be part of its containment tree. Writing fails with a
     * {@link JsonMappingException} for a reference to an object outside of that tree (for instance
     * an object that is not contained anywhere), reading fails for an id that does not occur in the
     * input. With references disabled, the module reads and writes exactly as before.</p>
     * <p>Generate a matching JSON schema with
     * {@link VMFJsonSchemaGenerator#withReferences(boolean)} enabled as well.</p>
     *
     * @param enabled {@code true} to read and write non-containment references
     * @return this module
     */
    public VMFJacksonModule withReferences(boolean enabled) {
        this.referencesEnabled = enabled;
        return this;
    }

    /**
     * Indicates whether non-containment references are read and written.
     * @return {@code true} if references are enabled
     * @see #withReferences(boolean)
     */
    public boolean isReferencesEnabled() {
        return referencesEnabled;
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
     * Constructor. Creates a new instance of VMFJacksonModule.
     */
    private VMFJacksonModule() {
        setDeserializerModifier(new GenericBuilderDeserializerModifier(this));
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
                if (Change.class.isAssignableFrom(beanDesc.getBeanClass())) {
                    return LIVE_CHANGE_SERIALIZER;
                }
                if (isVMFObj(beanDesc.getBeanClass())) {
                    return new GenericBuilderSerializer<>(serializer, VMFJacksonModule.this);
                }
                return serializer;
            }

            private boolean hasBuilder(Class<?> clazz) {
                try {
                    clazz.getDeclaredMethod("newBuilder");
                    return true;
                } catch (NoSuchMethodException e) {
                    return false;
                }
            }
        });
    }

    /**
     * Adds a type alias to the module and returns the module for chaining. A type alias maps a short
     * type name to a fully qualified class name; it is written to and read from the {@code @vmf-type}
     * discriminator during (de)serialization of polymorphic values.
     *
     * <p>Example:
     * <pre>{@code
     * module.withTypeAlias("person", Person.class.getName())
     *       .withTypeAlias("employee", Employee.class.getName());
     * }</pre>
     * <p>This adds the type alias {@code person} that maps to the {@code Person} class.
     */
    public VMFJacksonModule withTypeAlias(String alias, String className) {
        addTypeAlias(alias, className);
        return this;
    }

    /**
     * Adds multiple type aliases to the module at once and returns the module for chaining.
     * <p>Example:
     * <pre>{@code
     * Map<String, String> typeAliases = new HashMap<>();
     * typeAliases.put("person", Person.class.getName());
     * typeAliases.put("employee", Employee.class.getName());
     * module.withTypeAliases(typeAliases);
     * }</pre></p>
     * <p>This adds two type aliases {@code person} and {@code employee} that map to the
     * {@code Person} and {@code Employee} classes.</p>
     * @param typeAliases the type aliases to add
     * @return this module
     */
    public VMFJacksonModule withTypeAliases(Map<String, String> typeAliases) {
        typeAliases.forEach(this::addTypeAlias);
        return this;
    }

    /**
     * Creates a new experimental instance of the module.
     * @param runtimeType must be {@link RUNTIME_TYPE#EXPERIMENTAL} to acknowledge that this is
     *                    experimental software
     * @return a new module instance
     */
    public static VMFJacksonModule newInstance(RUNTIME_TYPE runtimeType) {
        if(!RUNTIME_TYPE.EXPERIMENTAL.equals(runtimeType)) {
            throw new RuntimeException("Please confirm that you are using an experimental piece of software by calling" +
                    " 'newInstance(RUNTIME_TYPE.EXPERIMENTAL)'.");
        }
        var module = new VMFJacksonModule();
        return module;
    }

    /**
     * Always throws: the module is experimental and must be created via
     * {@link #newInstance(RUNTIME_TYPE)} with {@link RUNTIME_TYPE#EXPERIMENTAL}.
     * @return never returns normally
     */
    public static VMFJacksonModule newInstance() {
        throw new RuntimeException("Please confirm that you are using an experimental piece of software by calling" +
                " 'newInstance(RUNTIME_TYPE.EXPERIMENTAL)'.");

//        var module = new VMFJacksonModule();
//        return module;
    }

    /**
     * Checks if the given object is an instance of the specified class name.
     *
     * @param obj The object to check.
     * @param className The fully qualified name of the class.
     * @return true if the object is an instance of the specified class, false otherwise.
     */
    public static boolean isInstanceOf(Object obj, String className) {
        if (obj == null || className == null) {
            return false;
        }

        try {
            // Get the class dynamically
            Class<?> clazz = Class.forName(className);

            // Check if the object is an instance of the dynamically obtained class
            return clazz.isInstance(obj);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Check if the class is a VMF object.
     * @param clazz the class to check
     * @return {@code true} if the class is a VMF object, {@code false} otherwise
     */
    static boolean isVMFObj(Class<?> clazz) {
        // check if extends VObject
        return eu.mihosoft.vmf.runtime.core.VObject.class.isAssignableFrom(clazz);
    }

    /**
     * Check if the object is a VMF object.
     * @param o the object to check
     * @return {@code true} if the object is a VMF object, {@code false} otherwise
     */
    static boolean isVMFObj(Object o) {
        return o instanceof eu.mihosoft.vmf.runtime.core.VObject;
    }


    /**
     * Generic builder deserializer modifier. This deserializer modifier is used to modify the deserializer of VMF objects
     * that have a builder. It checks if the object is a VMF object and has a builder. If so, it replaces the default
     * deserializer with a GenericBuilderDeserializer.
     */
    private static class GenericBuilderDeserializerModifier extends BeanDeserializerModifier {

        private final VMFJacksonModule module;

        public GenericBuilderDeserializerModifier(VMFJacksonModule module) {
            this.module = module;
        }

        @Override
        public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc,
                                                      JsonDeserializer<?> deserializer) {
            if (Change.class.isAssignableFrom(beanDesc.getBeanClass())) {
                return LIVE_CHANGE_DESERIALIZER;
            }
            if (isVMFObj(beanDesc.getBeanClass()) && hasBuilder(beanDesc.getBeanClass())) {

                return new GenericBuilderDeserializer<>(beanDesc.getBeanClass(), module);
            }

            return deserializer;
        }

        /**
         * Check if the class has a builder.
         * @param clazz the class to check
         * @return {@code true} if the class has a builder, {@code false} otherwise
         */
        private boolean hasBuilder(Class<?> clazz) {
            try {
                clazz.getDeclaredMethod("newBuilder");
                return true;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    }

    /**
     * Generic builder deserializer. This deserializer is used to deserialize VMF objects that have a builder. It uses
     * reflection to determine the builder methods and their corresponding fields. The deserializer is able to handle
     * scalar values, complex values, and collections. It also supports polymorphism by looking for a special field
     * {@code @vmf-type} that specifies the actual type of the object.
     * @param <T> the type of the object to deserialize
     */
    private static class GenericBuilderDeserializer<T> extends StdDeserializer<T> {

        private static final com.fasterxml.jackson.databind.deser.std.JsonNodeDeserializer STRICT_MODEL_TREE =
                new com.fasterxml.jackson.databind.deser.std.JsonNodeDeserializer() {
                    @Override
                    protected void _handleDuplicateField(JsonParser parser, DeserializationContext context,
                            com.fasterxml.jackson.databind.node.JsonNodeFactory factory, String name,
                            com.fasterxml.jackson.databind.node.ObjectNode object, JsonNode oldValue, JsonNode newValue)
                            throws IOException {
                        throw JsonMappingException.from(parser, "Duplicate field '" + name + "' in VMF model");
                    }
                };

        private static final ClassValue<BuilderPlan> BUILDER_PLANS = new ClassValue<>() {
            @Override
            protected BuilderPlan computeValue(Class<?> modelClass) {
                try {
                    return new BuilderPlan(modelClass);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalArgumentException("Cannot inspect the builder of " + modelClass.getName(), failure);
                }
            }
        };

        private final VMFJacksonModule module;

        /**
         * Constructor. Creates a new instance of GenericBuilderDeserializer.
         * @param vc the value class
         */
        public GenericBuilderDeserializer(Class<?> vc, VMFJacksonModule module) {
            super(vc);
            this.module = module;

        }


        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {

            JsonNode node;
            String format = p.getCodec() == null ? "" : p.getCodec().getFactory().getFormatName();
            if ("JSON".equals(format) || "YAML".equals(format)) {
                // Read this model strictly without changing mapper or caller parser settings. Retain very
                // small decimal literals until their declared scalar type is available for a range check.
                var numbers = new com.fasterxml.jackson.core.util.JsonParserDelegate(p) {
                    @Override public NumberTypeFP getNumberTypeFP() throws IOException {
                        NumberTypeFP type = super.getNumberTypeFP();
                        if (type != NumberTypeFP.BIG_DECIMAL && getDoubleValue() == 0d && getDecimalValue().signum() != 0) {
                            return NumberTypeFP.BIG_DECIMAL;
                        }
                        return type;
                    }
                };
                if (numbers.currentToken() == null) numbers.nextToken();
                node = STRICT_MODEL_TREE.deserialize(numbers, ctxt);
            } else {
                node = p.readValueAsTree();
            }

            if (!module.isReferencesEnabled()) {
                return deserializeNode(p, ctxt, node, null);
            }

            // the outermost VMF object owns the session: references are assigned when it is
            // complete, i.e., after all objects of its containment tree have been read
            VMFReferences.ReadSession session = VMFReferences.currentReadSession();
            if (session != null) {
                return deserializeNode(p, ctxt, node, session);
            }

            session = VMFReferences.startReadSession();
            try {
                T result = deserializeNode(p, ctxt, node, session);
                session.resolve(p);
                return result;
            } finally {
                VMFReferences.endReadSession();
            }
        }

        private T deserializeNode(JsonParser p, DeserializationContext ctxt, JsonNode node,
                                  VMFReferences.ReadSession session) throws IOException {
            Class<?> actualClass = _valueClass;

            // iterate over node fields and look for "@vmf-type" field to determine the actual type
            // do so and allow reiteration over the fields

            actualClass = getVMFTypeClass(p, node, actualClass);
            // Nested objects are often read from a tree parser; its codec retains the source format.
            boolean isXml = p.getCodec() != null && "XML".equals(p.getCodec().getFactory().getFormatName());

            // non-containment references of this object (only if references are enabled): they are
            // not passed to the builder but assigned when the outermost object is complete
            final Map<VMFReferences.ReferenceProperty, List<Object>> references = new LinkedHashMap<>();
            final Map<String, List<Object>> keyedContents = new LinkedHashMap<>();

            final Object result;
            try {

                final Map<String, VMFReferences.ReferenceProperty> referenceProperties = session == null
                        ? Collections.emptyMap() : VMFReferences.referenceProperties(actualClass);

                BuilderPlan plan = BUILDER_PLANS.get(actualClass);
                Object builder = plan.newInstance.invoke(null);
                Map<String, BuilderSetter> methodMap = plan.fieldMethods();

                // Iterate over JSON fields and invoke corresponding builder methods
                node.fields().forEachRemaining(entry -> {
                    String fieldName = entry.getKey();
                    
                    JsonNode value = entry.getValue();

                    // reference property that holds ids (references enabled)
                    VMFReferences.ReferenceProperty referenceProperty = referenceProperties.get(fieldName);
                    if (referenceProperty != null
                            && VMFReferences.holdsReferences(value, referenceProperty, isXml)) {
                        try {
                            references.put(referenceProperty, referenceSlots(ctxt, value, referenceProperty, isXml));
                        } catch (IOException e) {
                            throw new RuntimeException(
                                    "Failed to deserialize references of field '" + fieldName + "'", e);
                        }
                        return;
                    }

                    // check if we have a method for the field
                    if (methodMap.containsKey(fieldName)) {
                        BuilderSetter setter = methodMap.get(fieldName);
                        Method method = setter.method;
                        try {
                            // Determine the parameter type
                            Class<?> paramType = method.getParameterTypes()[0];

                            if (setter.property != null && setter.property.getType().isSetType()) {
                                if (!isXml && !value.isArray()) throw new IOException("Expected an array of set members");
                                JavaType elementType = ctxt.constructType(method.getGenericParameterTypes()[0]).getContentType();
                                java.util.Set<Object> members;
                                if (isXml) {
                                    members = ManagedSetXml.read(value, elementType, ctxt);
                                } else {
                                    members = new java.util.LinkedHashSet<>();
                                    for (JsonNode member : value) {
                                        Object decoded = TypedMapEntries.scalar(member, elementType, ctxt);
                                        if (decoded == null || !members.add(decoded)) throw new IOException("Null or duplicate set member");
                                    }
                                }
                                method.invoke(builder, members);
                            } else if (Collection.class.isAssignableFrom(paramType)) {
                                // Get the collection type
                                var genericParamType = method.getGenericParameterTypes()[0];
                                Class<?> elementTypeClass = ctxt.constructType(genericParamType).getContentType().getRawClass();

                                // Create a new collection (we assume it's a List)
                                List<Object> collection = new ArrayList<>();
                                // Iterate over the elements
                                for (JsonNode element : value) {
                                    // in case of xml we have further nesting because of the use of <item> tags within
                                    // the collection which isn't done in case of json or yaml
                                    if(isXml && element instanceof ArrayNode) {
                                        var arrayNode = (ArrayNode) element;
                                        for (JsonNode e : arrayNode) {
                                            // Deserialize the collection element
                                            Object elementValue = e.isValueNode()
                                                    // we reuse the scalar value handling from the deserializeField method
                                                    // it won't use the other parts of the method since we only use it for
                                                    // scalar values
                                                    ? deserializeField(ctxt, e, elementTypeClass)
                                                    : ctxt.readValue(
                                                    e.traverse(ctxt.getParser().getCodec()),
                                                    getVMFTypeClass(ctxt.getParser(), e, elementTypeClass));

                                            // Add the element to the collection
                                            collection.add(elementValue);
                                        }
                                    } else {
                                        // Deserialize the collection element
                                        Object elementValue = element.isValueNode()
                                                // we reuse the scalar value handling from the deserializeField method
                                                // it won't use the other parts of the method since we only use it for
                                                // scalar values
                                                ? deserializeField(ctxt, element, elementTypeClass)
                                                : ctxt.readValue(
                                                        element.traverse(ctxt.getParser().getCodec()),
                                                        getVMFTypeClass(ctxt.getParser(), element, elementTypeClass));

                                        // Add the element to the collection
                                        collection.add(elementValue);
                                    }
                                }
                                // Invoke the builder method
                                if (session != null && setter.property != null
                                        && needsReferenceKey(setter.property, collection)) {
                                    keyedContents.put(setter.property.getName(), collection);
                                } else {
                                    method.invoke(builder, collection);
                                }
                            } else if (Map.class.isAssignableFrom(paramType)
                                    && method.getGenericParameterTypes()[0] instanceof ParameterizedType) {
                                JavaType declaredType = ctxt.getTypeFactory().constructType(method.getGenericParameterTypes()[0]);
                                if (isXml && setter.property != null && setter.property.getType().isMapType()) {
                                    method.invoke(builder, ManagedMapXml.read(value, declaredType, ctxt));
                                } else if (setter.property != null && setter.property.getType().isMapType()
                                        && !declaredType.getKeyType().hasRawClass(String.class)) {
                                    method.invoke(builder, TypedMapEntries.read(value, declaredType, ctxt));
                                } else {
                                    method.invoke(builder, (Object) ctxt.readTreeAsValue(value, declaredType));
                                }
                            } else {
                                // Scalar model properties need the same polymorphic type
                                // resolution as collection elements, including immutable
                                // implementations of an interface-only declared type.
                                Class<?> valueType = isVMFObj(paramType)
                                        ? getVMFTypeClass(ctxt.getParser(), value, paramType) : paramType;
                                var paramValue = deserializeField(ctxt, value, valueType);
                                method.invoke(builder, paramValue);
                            }
                        } catch (Exception e) {
                            // do not swallow: a field that fails to deserialize would otherwise be
                            // silently dropped, causing hard-to-diagnose data loss. Propagate so the
                            // enclosing handler reports it as an IOException.
                            throw new RuntimeException(
                                    "Failed to deserialize field '" + fieldName + "'", e);
                        }
                    } else {
                        // no builder method matches this field (e.g. the '@vmf-type' discriminator or
                        // an unknown/removed property); such fields are intentionally ignored.
                    }
                });

                // Build the final object
                result = plan.build.invoke(builder);
            } catch (Exception e) {
                // a JsonMappingException (not a plain IOException): ObjectMapper.readValue(String, ..)
                // replaces plain IOExceptions by an exception without cause, which hides the reason
                throw JsonMappingException.from(p, "Error deserializing object of type " + actualClass.getName(), e);
            }

            if (session != null) {
                keyedContents.forEach((name, entries) -> session.addKeyedContents((VObject) result, name, entries));
                String id = VMFReferences.idOf(node, isXml);
                if (id != null) {
                    session.register(id, result, p);
                }
                for (Map.Entry<VMFReferences.ReferenceProperty, List<Object>> r : references.entrySet()) {
                    session.addReference((VObject) result, r.getKey(), r.getValue());
                }
            }

            return (T) result;
        }

        private static boolean needsReferenceKey(eu.mihosoft.vmf.runtime.core.Property property, List<?> entries) {
            var key = property.getKeyPropertyName();
            if (key.isEmpty()) return false;
            for (Object entry : entries) {
                if (!(entry instanceof VObject)) continue;
                var keyProperty = ((VObject) entry).vmf().reflect().propertyByName(key.get()).orElseThrow();
                if (keyProperty.getType().isModelType() && keyProperty.get() == null) return true;
            }
            return false;
        }

        /**
         * Reads the value of a reference property: ids become {@link VMFReferences.IdSlot}s that are
         * resolved later, values written by value (immutable objects) are read right away.
         */
        private List<Object> referenceSlots(DeserializationContext ctxt, JsonNode value,
                                            VMFReferences.ReferenceProperty property, boolean isXml) throws IOException {
            List<JsonNode> elements = property.list
                    ? VMFReferences.elementsOf(value, isXml) : Collections.singletonList(value);
            List<Object> slots = new ArrayList<>(elements.size());
            for (JsonNode element : elements) {
                if (element == null || element.isNull()
                        || (element.isTextual() && element.asText().isEmpty())) {
                    slots.add(null);
                } else if (VMFReferences.isIdNode(element)) {
                    slots.add(new VMFReferences.IdSlot(element.asText()));
                } else {
                    slots.add(ctxt.readValue(
                            element.traverse(ctxt.getParser().getCodec()),
                            getVMFTypeClass(ctxt.getParser(), element, property.valueClass)));
                }
            }
            return slots;
        }

        private static final class BuilderPlan {
            private final boolean modelType;
            private final Method newInstance;
            private final Method build;
            private final Locale describedLocale;
            private final Map<String, Property> declarations;
            private final List<BuilderSetter> setters;

            private BuilderPlan(Class<?> modelClass) throws ReflectiveOperationException {
                modelType = isVMFObj(modelClass);
                Class<?> builderClass = VMFTypeUtils.getBuilderClass(modelClass);
                newInstance = builderClass.getDeclaredMethod("newInstance");
                build = builderClass.getDeclaredMethod("build");
                var methods = new ArrayList<Method>();
                for (Method method : builderClass.getDeclaredMethods()) {
                    if (method.getName().startsWith("with") && method.getParameterCount() == 1) {
                        Class<?> parameter = method.getParameterTypes()[0];
                        if (!parameter.isArray() && !eu.mihosoft.vmf.runtime.core.Builder.class.isAssignableFrom(parameter)) {
                            methods.add(method);
                        }
                    }
                }

                declarations = modelType && !methods.isEmpty() ? declaredProperties(modelClass) : Map.of();
                describedLocale = Locale.getDefault();
                setters = new ArrayList<>(methods.size());
                for (Method method : methods) {
                    String name = setterName(method, describedLocale);
                    setters.add(new BuilderSetter(method, name, declaredProperty(method, name)));
                }
            }

            private Map<String, Property> declaredProperties(Class<?> modelClass) {
                try {
                    Method method = modelClass.getDeclaredMethod("type");
                    if (!Modifier.isStatic(method.getModifiers())
                            || method.getReturnType() != eu.mihosoft.vmf.runtime.core.Type.class) {
                        return Map.of();
                    }
                    var type = (eu.mihosoft.vmf.runtime.core.Type) method.invoke(null);
                    if (type == null || !type.isModelType() || type.isInterfaceOnly()
                            || !modelClass.getName().equals(type.getName())) {
                        return Map.of();
                    }

                    // Public type reflection has a caller-mutable property list.
                    // Inspect a private prototype once and leave that list alone.
                    var prototype = (VObject) build.invoke(newInstance.invoke(null));
                    Reflect reflect = prototype.vmf().reflect();
                    if (reflect.getClass() != ReflectImpl.class) return Map.of();
                    Map<String, Property> properties = new HashMap<>();
                    for (Property property : ReflectImpl.readProperties(reflect)) {
                        properties.putIfAbsent(property.getName(), property);
                    }
                    return properties;
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    return Map.of();
                }
            }

            private Property declaredProperty(Method method, String name) {
                if (declarations.isEmpty()) return null;
                Property property = declarations.get(name);
                if (property != null) return property;
                property = declarations.get(setterName(method, Locale.ROOT));
                if (property != null) return property;

                // Older models may have been generated under a different locale.
                String suffix = method.getName().substring(4);
                for (Property declaration : declarations.values()) {
                    if (!suffix.equalsIgnoreCase(declaration.getName())) continue;
                    if (property != null) return null;
                    property = declaration;
                }
                return property;
            }

            private static String setterName(Method method, Locale locale) {
                String name = method.getName();
                return name.substring(4, 5).toLowerCase(locale) + name.substring(5);
            }

            private Map<String, BuilderSetter> fieldMethods() throws ReflectiveOperationException {
                Map<String, BuilderSetter> result = new HashMap<>();
                Locale locale = Locale.getDefault();
                boolean useDescribedFields = describedLocale.equals(locale);
                for (BuilderSetter setter : setters) {
                    String name = useDescribedFields ? setter.name : setterName(setter.method, locale);
                    Property property = useDescribedFields ? setter.property : declaredProperty(setter.method, name);
                    if (modelType && property == null) {
                        // A hand-written builder can supply its own instance metadata.
                        var prototype = (VObject) build.invoke(newInstance.invoke(null));
                        property = prototype.vmf().reflect().propertyByName(name).orElseThrow();
                    }
                    // Read the current annotations because generated reflection exposes them.
                    if (property != null) name = VMFTypeUtils.getFieldNameForProperty(property);
                    result.put(name, setter.property == property ? setter : new BuilderSetter(setter.method, name, property));
                }
                return result;
            }
        }

        private static final class BuilderSetter {
            private final Method method;
            private final String name;
            private final Property property;

            private BuilderSetter(Method method, String name, Property property) {
                this.method = method;
                this.name = name;
                this.property = property;
            }
        }

        private static Object deserializeField(DeserializationContext ctxt, JsonNode value, Class<?> paramType) throws IllegalAccessException, InvocationTargetException, IOException {
            // Handle null values
            if (value.isNull()) {
                return null;
            } else if (value.isValueNode()) {
                // Deserialize scalar value
                Object paramValue;
                if (paramType == String.class) {
                    paramValue = value.asText();
                } else if (paramType == Integer.class|| paramType == int.class) {
                    paramValue = value.asInt();
                } else if (paramType == Long.class|| paramType == long.class) {
                    paramValue = value.asLong();
                } else if (paramType == Double.class|| paramType == double.class) {
                    paramValue = value.asDouble();
                } else if (paramType == Boolean.class|| paramType == boolean.class) {
                    paramValue = value.asBoolean();
                } else if(paramType.isEnum()) {
                    paramValue = Enum.valueOf((Class<Enum>) paramType, value.asText());
                } else {
                    // Deserialize non-scalar value
                    paramValue = ctxt.getParser().getCodec().treeToValue(value, paramType);
                }
                return paramValue;
            } else {
                // Deserialize complex value
                Object paramValue = ctxt.getParser().getCodec().treeToValue(value, paramType);
                return paramValue;
            }
        }

        private Class<?> getVMFTypeClass(JsonParser p, JsonNode node, Class<?> actualClass) throws JsonMappingException {
            String vmfTypeFieldName = "@vmf-type";

            var clsName = "com.fasterxml.jackson.dataformat.xml.deser.FromXmlParser";

            if(isInstanceOf(p, clsName)) {
                vmfTypeFieldName = "vmf-type";
            }

            JsonNode discriminator = node.get(vmfTypeFieldName);
            if (discriminator == null) {
                return actualClass;
            }

            ModelRegistry registry = module.modelRegistry;
            if (registry != null) {
                // External collection elements may use this field as ordinary data.
                if (actualClass == null || !isVMFObj(actualClass)) {
                    return actualClass;
                }
                if (!discriminator.isTextual() || discriminator.textValue().isBlank()) {
                    throw JsonMappingException.from(p, "Expected a non-empty VMF type name in '" + vmfTypeFieldName + "'");
                }
            }

            String name = discriminator.asText();
            String type = module.typeAliases.getOrDefault(name, name);
            if (registry != null) {
                Class<?> registeredClass = registry.findType(type)
                        .filter(descriptor -> !descriptor.isInterfaceOnly())
                        .flatMap(descriptor -> registry.findJavaType(descriptor.getName()))
                        .orElseThrow(() -> JsonMappingException.from(p, "Unregistered or interface-only VMF type '" + type + "'"));
                if (!actualClass.isAssignableFrom(registeredClass)) {
                    throw JsonMappingException.from(p, "VMF type '" + type + "' is not assignable to " + actualClass.getName());
                }
                return registeredClass;
            }

            try {
                return actualClass != null && isVMFObj(actualClass)
                        ? Class.forName(type, false, actualClass.getClassLoader()) : Class.forName(type);
            } catch (ClassNotFoundException e) {
                throw JsonMappingException.from(p, "Cannot resolve VMF type '" + type + "'", e);
            }
        }



    }

    /**
     * Generic builder serializer. This serializer is used to serialize VMF objects that have a builder. It checks for
     * the presence of the `@vmf-type` annotation and adds it to the serialized output.
     * @param <T> the type of the object to serialize
     */
    private static class GenericBuilderSerializer<T> extends StdSerializer<T> {

        private final JsonSerializer<T> defaultSerializer;
        private final VMFJacksonModule module;

        /**
         * Constructor. Creates a new instance of GenericBuilderSerializer.
         * @param defaultSerializer the default serializer
         */
        public GenericBuilderSerializer(JsonSerializer<T> defaultSerializer, VMFJacksonModule module) {
            super((Class<T>) defaultSerializer.handledType());
            this.defaultSerializer = defaultSerializer;
            this.module = module;
        }

        @Override
        public void serialize(T value, JsonGenerator gen, SerializerProvider provider) throws IOException {

            // if is VMF object, check for @vmf-type annotation
            boolean isVMFObject = isVMFObj(value);

            if (!isVMFObject) {
                defaultSerializer.serialize(value, gen, provider);
                return;
            }

            eu.mihosoft.vmf.runtime.core.VObject obj = (eu.mihosoft.vmf.runtime.core.VObject) value;

            if (!module.isReferencesEnabled()) {
                serializeObject(obj, gen, null);
                return;
            }

            // the outermost VMF object owns the session: it assigns the ids of all referenced
            // objects in its containment tree before anything is written
            VMFReferences.WriteSession session = VMFReferences.currentWriteSession();
            if (session != null) {
                serializeObject(obj, gen, session);
                return;
            }

            session = VMFReferences.startWriteSession(obj, gen);
            try {
                serializeObject(obj, gen, session);
            } finally {
                VMFReferences.endWriteSession();
            }
        }

        private void serializeObject(eu.mihosoft.vmf.runtime.core.VObject obj, JsonGenerator gen,
                                     VMFReferences.WriteSession session) throws IOException {

            if(gen.getOutputContext().inRoot()) {
                // set start name
                String startName = obj.vmf().reflect().type().getName();

                // check type alias
                if (module.getTypeAliasesReverse().containsKey(startName)) {
                    startName = module.getTypeAliasesReverse().get(startName);
                }

                // Start writing the object using the start name in case of xml
                var clsName = "com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator";
                if(isInstanceOf(gen, clsName)) {
                    try {
                        // doing via reflection to prevent dependency: ((ToXmlGenerator) gen).setNextName(new QName(startName));

                        // Get the class dynamically
                        Class<?> clazz = Class.forName(clsName);

                        // Get the setNextName method
                        Method setNextNameMethod = clazz.getMethod("setNextName", QName.class);

                        // Invoke the setNextName method
                        setNextNameMethod.invoke(gen, new QName(startName));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            gen.writeStartObject();

            // Write the type annotation if the model type is polymorphic
            if (VMFTypeUtils.isTypeExtendsModelTypeInProps(obj, obj.vmf().reflect().type())) {

                String typeFieldName = "@vmf-type";

                var clsName = "com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator";

                if(isInstanceOf(gen, clsName)) {
                    typeFieldName = "vmf-type";
                }

                gen.writeFieldName(typeFieldName);

                // check if we have a type alias
                String typeName = obj.vmf().reflect().type().getName();
                if(module.getTypeAliases().containsValue(typeName)) {
                    typeName = module.getTypeAliasesReverse().get(typeName);
                }

                gen.writeString(typeName);
            }

            // Write the id if the object is referenced (references enabled)
            String id = session == null ? null : session.idOf(obj);
            if (id != null) {
                gen.writeFieldName(isInstanceOf(gen, "com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator")
                        ? VMFReferences.ID_FIELD_XML : VMFReferences.ID_FIELD);
                gen.writeString(id);
            }

            // For each property, serialize the value
            for (eu.mihosoft.vmf.runtime.core.Property p : obj.vmf().reflect().properties()) {
                Object propValue = p.get();
                if (propValue == null) {
                    continue;
                }
                // if property holds contained value or is external or immutable, serialize it
                if (VMFTypeUtils.isParentOfPropContainer(p)
                        || !p.getType().isModelType()
                        || propValue instanceof eu.mihosoft.vmf.runtime.core.Immutable
                        || isImmutableModelList(p, obj)) {
                    if (session == null && p.getKeyPropertyName().isPresent()) {
                        for (Object item : (List<?>) propValue) {
                            var key = ((VObject) item).vmf().reflect()
                                    .propertyByName(p.getKeyPropertyName().get()).orElseThrow();
                            if (VMFReferences.isReferenceProperty(key) && VMFReferences.isWrittenAsReference(key.get())) {
                                throw JsonMappingException.from(gen, "Model reference key on " + p.getName()
                                        + " requires VMFJacksonModule.withReferences(true)");
                            }
                        }
                    }
                    gen.writeFieldName(VMFTypeUtils.getFieldNameForProperty(p));
                    if (p.getType().isSetType()
                            && isInstanceOf(gen, "com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator")) {
                        ManagedSetXml.write((java.util.Set<?>) propValue, gen);
                    } else if (p.getType().isMapType()
                            && isInstanceOf(gen, "com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator")) {
                        ManagedMapXml.write((Map<?, ?>) propValue, gen);
                    } else if (p.getType().isMapType() && !String.class.getName().equals(
                            p.getType().getDescriptor().getArguments().get(0).getName())) {
                        TypedMapEntries.write((Map<?, ?>) propValue, gen);
                    } else {
                        gen.writeObject(propValue);
                    }
                } else if (session != null && VMFReferences.isReferenceProperty(p)) {
                    // non-containment reference (references enabled): ids instead of objects
                    gen.writeFieldName(VMFTypeUtils.getFieldNameForProperty(p));
                    gen.writeObject(VMFReferences.referenceValue(session, obj, p, propValue, gen));
                }
            }

            // End writing the object
            gen.writeEndObject();
        }

        private static boolean isImmutableModelList(eu.mihosoft.vmf.runtime.core.Property property, VObject owner)
                throws IOException {
            if (!property.getType().isListType()) return false;
            String element = property.getType().getElementTypeName().orElseThrow();
            try {
                return eu.mihosoft.vmf.runtime.core.Immutable.class.isAssignableFrom(
                        Class.forName(element, false, owner.getClass().getClassLoader()));
            } catch (ClassNotFoundException exception) {
                throw new IOException("Unknown list element type " + element, exception);
            }
        }
    }
}
