package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NumericNode;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.Immutable;
import eu.mihosoft.vmf.runtime.core.ModelRegistry;
import eu.mihosoft.vmf.runtime.core.ModelValidator;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.Reflect;
import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;
import eu.mihosoft.vmf.runtime.core.VObject;

import javax.xml.namespace.QName;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

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
 * contained objects, external values and immutable values. A non-containment reference to a mutable object
 * ({@code @Refers} properties and other model-typed properties that are neither contained nor a container)
 * cannot be represented, and writing such a model fails. With
 * {@link #withReferences(boolean) withReferences(true)} references are written as ids and restored by
 * identity; with {@link #withReferences(ReferenceMode) withReferences(ReferenceMode.OMIT)} they are left out.</p>
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

    /**
     * How non-containment references to mutable model objects are written. References to immutable values are
     * always written by value.
     */
    public enum ReferenceMode {
        /** As the {@code @vmf-id} of their targets, restored by identity when reading. */
        IDS,
        /** Not at all: writing a model that has such a reference fails. The default. */
        REJECT,
        /** Not at all: such references are left out of the document, which then holds the containment tree only. */
        OMIT
    }

    private final Map<String, String> typeAliases = new HashMap<>();
    private final Map<String, String> typeAliasesReverse = new HashMap<>();

    private volatile ReferenceMode referenceMode = ReferenceMode.REJECT;
    private volatile boolean rootTypeDiscriminator;
    private volatile ModelRegistry modelRegistry;
    private volatile ModelValidator modelValidator;
    private final ThreadLocal<Boolean> readingModelForValidation = new ThreadLocal<>();

    /**
     * Validates each outermost VMF model after decoding, including reference resolution when enabled.
     * By default no model validation is invoked during deserialization.
     * @param validator the configured VMF validator
     * @return this module
     * @throws NullPointerException if validator is null
     */
    public VMFJacksonModule withModelValidator(ModelValidator validator) {
        this.modelValidator = Objects.requireNonNull(validator, "validator");
        return this;
    }

    /**
     * Writes a concrete type name even when a root would ordinarily omit it.
     * Enable this when the consumer will read through an interface-only role.
     * @param enabled whether to write a discriminator for every root model
     * @return this module
     */
    public VMFJacksonModule withRootTypeDiscriminator(boolean enabled) {
        rootTypeDiscriminator = enabled;
        return this;
    }

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
     * objects with shared identity or membership in a unique reference end are defined inline with an id
     * on first use. Later uses write the id. Ordinary single-use immutable values are written by value.</p>
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
     * referenced mutable object must be part of its containment tree. Reading fails for an unknown id,
     * a duplicate definition, an incompatible target type or a cycle of immutable definitions.
     * With references disabled, immutable values and collections use ordinary nested object bodies.</p>
     * <p>Generate a matching JSON schema with
     * {@link VMFJsonSchemaGenerator#withReferences(boolean)} enabled as well.</p>
     *
     * @param enabled {@code true} to read and write non-containment references ({@link ReferenceMode#IDS}),
     *                {@code false} to reject them when writing ({@link ReferenceMode#REJECT})
     * @return this module
     */
    public VMFJacksonModule withReferences(boolean enabled) {
        return withReferences(enabled ? ReferenceMode.IDS : ReferenceMode.REJECT);
    }

    /**
     * Selects how non-containment references to mutable model objects are written. Use
     * {@link ReferenceMode#OMIT} to write the containment tree of a model that has such references.
     *
     * @param mode the reference mode
     * @return this module
     */
    public VMFJacksonModule withReferences(ReferenceMode mode) {
        this.referenceMode = java.util.Objects.requireNonNull(mode, "mode");
        return this;
    }

    /**
     * Returns how non-containment references to mutable model objects are written.
     * @return the reference mode
     */
    public ReferenceMode getReferenceMode() {
        return referenceMode;
    }

    /**
     * Indicates whether non-containment references are read and written.
     * @return {@code true} if the reference mode is {@link ReferenceMode#IDS}
     * @see #withReferences(boolean)
     */
    public boolean isReferencesEnabled() {
        return referenceMode == ReferenceMode.IDS;
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
     * Registers aliases declared with the VMF annotation key {@code vmf:wire:type}.
     * @param modelType a generated concrete type from the model
     * @return this module
     */
    public VMFJacksonModule withModelWireTypeAliases(Class<? extends VObject> modelType) {
        return withTypeAliases(VMFTypeUtils.modelWireTypeAliases(modelType, typeAliases));
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
            Class<?> modelClass = beanDesc.getBeanClass();
            if (isVMFObj(modelClass)) {
                if (hasBuilder(modelClass)) return new GenericBuilderDeserializer<>(modelClass, module, false);
                if (VMFTypeUtils.interfaceOnlyDeclaration(modelClass).isPresent())
                    return new GenericBuilderDeserializer<>(modelClass, module, true);
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
        private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?[0-9]+");
        private static final Pattern DECIMAL_TEXT = Pattern.compile(
                "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

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
        private final boolean interfaceOnly;

        private GenericBuilderDeserializer(Class<?> modelClass, VMFJacksonModule module, boolean interfaceOnly) {
            super(modelClass);
            this.module = module;
            this.interfaceOnly = interfaceOnly;
        }


        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {

            JsonNode node;
            String format = p.getCodec() == null ? "" : p.getCodec().getFactory().getFormatName();
            if ("JSON".equals(format) || "YAML".equals(format)) {
                // Read this model strictly without changing mapper or caller parser settings. Retain
                // decimals that would underflow or overflow double until their declared type is known.
                var numbers = new com.fasterxml.jackson.core.util.JsonParserDelegate(p) {
                    @Override public NumberTypeFP getNumberTypeFP() throws IOException {
                        NumberTypeFP type = super.getNumberTypeFP();
                        if (type != NumberTypeFP.BIG_DECIMAL && !isNaN()) {
                            double rounded = getDoubleValue();
                            if (!Double.isFinite(rounded) || (rounded == 0d && getDecimalValue().signum() != 0)) {
                                return NumberTypeFP.BIG_DECIMAL;
                            }
                        }
                        return type;
                    }
                };
                if (numbers.currentToken() == null) numbers.nextToken();
                node = STRICT_MODEL_TREE.deserialize(numbers, ctxt);
            } else {
                node = p.readValueAsTree();
            }

            if (isVMFObj(_valueClass) && !node.isObject()) {
                if ("XML".equals(format) && node.isTextual() && node.asText().isEmpty())
                    node = ctxt.getNodeFactory().objectNode();
                else
                    throw JsonMappingException.from(p, "Expected a model object for " + _valueClass.getName());
            }
            ModelValidator validator = module.modelValidator;
            if (!module.isReferencesEnabled()) {
                if (validator == null || module.readingModelForValidation.get() != null) {
                    return deserializeNode(p, ctxt, node, null);
                }
                T result;
                module.readingModelForValidation.set(Boolean.TRUE);
                try {
                    result = deserializeNode(p, ctxt, node, null);
                } finally {
                    module.readingModelForValidation.remove();
                }
                return validateModel(p, validator, result);
            }

            // the outermost VMF object owns the session: references are assigned when it is
            // complete, i.e., after all objects of its containment tree have been read
            VMFReferences.ReadSession session = VMFReferences.currentReadSession();
            if (session != null) {
                return deserializeNode(p, ctxt, node, session);
            }

            ImmutableDefinitions definitions = new ImmutableDefinitions(p, ctxt, node, _valueClass);
            session = VMFReferences.startReadSession(definitions::load);
            T result;
            try {
                result = deserializeNode(p, ctxt, node, session);
                session.resolve(p);
            } finally {
                VMFReferences.endReadSession();
            }
            return validator == null ? result : validateModel(p, validator, result);
        }

        private T validateModel(JsonParser parser, ModelValidator validator, T result) throws VMFValidationException {
            var report = validator.validate((VObject) result);
            if (!report.isValid()) throw new VMFValidationException(parser, report);
            return result;
        }

        private T deserializeNode(JsonParser p, DeserializationContext ctxt, JsonNode node,
                                  VMFReferences.ReadSession session) throws IOException {
            Class<?> actualClass = getVMFTypeClass(p, node, _valueClass);
            boolean xml = p.getCodec() != null && "XML".equals(p.getCodec().getFactory().getFormatName());
            if (session != null && Immutable.class.isAssignableFrom(actualClass)) {
                String id = VMFReferences.idOf(node, xml, p);
                if (id != null) return (T) session.immutable(id, actualClass, p);
            }
            return buildNode(p, ctxt, node, actualClass, session);
        }

        /** Index only declared model properties, so external data and map keys never define VMF ids. */
        private final class ImmutableDefinitions {
            private final JsonParser parser;
            private final DeserializationContext context;
            private final JsonNode root;
            private final Class<?> rootType;
            private final boolean xml;
            private final Map<String, JsonNode> nodes = new HashMap<>();
            private final Map<String, Class<?>> types = new HashMap<>();
            private boolean indexed;

            ImmutableDefinitions(JsonParser parser, DeserializationContext context, JsonNode root, Class<?> rootType) {
                this.parser = parser;
                this.context = context;
                this.root = root;
                this.rootType = rootType;
                xml = parser.getCodec() != null && "XML".equals(parser.getCodec().getFactory().getFormatName());
            }

            Object load(String id) throws IOException {
                if (!indexed) {
                    index(root, rootType);
                    indexed = true;
                }
                JsonNode definition = nodes.get(id);
                if (definition == null) throw JsonMappingException.from(parser, "Unresolved immutable reference '" + id + "'");
                return buildNode(parser, context, definition, types.get(id), VMFReferences.currentReadSession());
            }

            private void index(JsonNode node, Class<?> declaredType) throws IOException {
                if (!node.isObject()) return;
                Class<?> actual = getVMFTypeClass(parser, node, declaredType);
                if (Immutable.class.isAssignableFrom(actual)) {
                    String id = VMFReferences.idOf(node, xml, parser);
                    if (id != null) {
                        if (nodes.putIfAbsent(id, node) != null)
                            throw JsonMappingException.from(parser, "Duplicate " + VMFReferences.ID_FIELD + " '" + id + "'");
                        types.put(id, actual);
                    }
                }
                try {
                    for (var entry : BUILDER_PLANS.get(actual).fieldMethods().entrySet()) {
                        Property property = entry.getValue().property;
                        JsonNode value = node.get(entry.getKey());
                        if (value == null || property == null || !property.getType().isModelType()) continue;
                        boolean many = property.getType().isReferenceSetType() || property.getType().isListType();
                        String name = many ? property.getType().getElementTypeName().orElseThrow() : property.getType().getName();
                        Class<?> target = Class.forName(name, false, actual.getClassLoader());
                        if (many) {
                            for (JsonNode member : VMFReferences.elementsOf(value, xml)) index(member, target);
                        } else {
                            index(value, target);
                        }
                    }
                } catch (ReflectiveOperationException ex) {
                    throw JsonMappingException.from(parser, "Cannot inspect model references on " + actual.getName(), ex);
                }
            }
        }

        private T buildNode(JsonParser p, DeserializationContext ctxt, JsonNode node, Class<?> actualClass,
                            VMFReferences.ReadSession session) throws IOException {
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
                            List<Object> slots = referenceSlots(ctxt, value, referenceProperty, isXml);
                            if (Immutable.class.isAssignableFrom(actualClass)) {
                                // Immutable owners must receive their complete targets during construction.
                                List<Object> targets = new ArrayList<>(slots.size());
                                for (Object slot : slots) {
                                    targets.add(slot instanceof VMFReferences.IdSlot
                                            ? session.immutable(((VMFReferences.IdSlot) slot).id, referenceProperty.valueClass, p) : slot);
                                }
                                methodMap.get(fieldName).method.invoke(builder, referenceProperty.list ? targets : targets.get(0));
                            } else {
                                if (referenceProperty.hasDeclaredDefault) {
                                    // An explicit field replaces the seeded membership. The builder keeps its declared reset value.
                                    methodMap.get(fieldName).method.invoke(builder, List.of());
                                }
                                references.put(referenceProperty, slots);
                            }
                        } catch (IOException | ReflectiveOperationException e) {
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
                            if (setter.property != null && setter.property.getType().isReferenceSetType()) {
                                if (!isXml && !value.isArray()) throw new IOException("Expected an array of model references");
                            }

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
                            } else if (!isXml && paramType == List.class && setter.property != null
                                    && !setter.property.getType().isListType()) {
                                // A declared external raw List is one host value, even though its builder parameter is a collection.
                                method.invoke(builder, readExternalList(ctxt, value));
                            } else if (Collection.class.isAssignableFrom(paramType)
                                    || (setter.property != null && setter.property.getType().isReferenceSetType() && paramType == Iterable.class)) {
                                boolean referenceSet = setter.property != null && setter.property.getType().isReferenceSetType();
                                Class<?> elementType = referenceSet ? Class.forName(
                                        setter.property.getType().getElementTypeName().orElseThrow(), false,
                                        method.getDeclaringClass().getClassLoader())
                                        : ctxt.constructType(method.getGenericParameterTypes()[0]).getContentType().getRawClass();
                                boolean externalRawList = !isXml && !referenceSet && elementType == List.class
                                        && setter.property != null && setter.property.getType().isListType();
                                boolean untypedList = !isXml && !referenceSet && elementType == Object.class
                                        && setter.property != null && setter.property.getType().isListType()
                                        && Object.class.getName().equals(
                                                setter.property.getType().getElementTypeName().orElse(null));
                                if (externalRawList && !value.isArray()) {
                                    throw new IOException("Expected an array of external lists");
                                }

                                List<Object> collection = new ArrayList<>();
                                // Plain collection declarations historically read members only from a container node.
                                if (referenceSet || value.isContainerNode()) {
                                    Iterable<JsonNode> members = isXml ? VMFReferences.elementsOf(value, true) : value;
                                    for (JsonNode member : members) {
                                        if (externalRawList) {
                                            collection.add(readExternalList(ctxt, member));
                                            continue;
                                        }
                                        if (untypedList && member.isContainerNode()) {
                                            collection.add(ctxt.readTreeAsValue(member, Object.class));
                                            continue;
                                        }
                                        if (referenceSet) {
                                            // XML has a text node for an empty <item/> model object.
                                            if (isXml && member.isTextual() && member.textValue().isEmpty())
                                                member = ctxt.getNodeFactory().objectNode();
                                            if (!member.isObject()) throw new IOException("Expected a model object in " + fieldName);
                                        }
                                        // An external element's object fields belong to Jackson or its host codec.
                                        Class<?> memberType = isVMFObj(elementType)
                                                ? getVMFTypeClass(ctxt.getParser(), member, elementType) : elementType;
                                        if (referenceSet && session == null && VMFReferences.isReferenceProperty(setter.property)
                                                && !Immutable.class.isAssignableFrom(memberType)) {
                                            throw new IOException("Mutable reference collection " + fieldName + " requires withReferences(true)");
                                        }
                                        collection.add(member.isValueNode() ? deserializeField(ctxt, member, elementType)
                                                : ctxt.readValue(member.traverse(ctxt.getParser().getCodec()), memberType));
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
                String id = VMFReferences.idOf(node, isXml, p);
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
            if (property.uniqueReferences && !isXml && !value.isArray())
                throw new IOException("Expected an array of target IDs for " + property.name);
            List<JsonNode> elements = property.list
                    ? VMFReferences.elementsOf(value, isXml) : Collections.singletonList(value);
            List<Object> slots = new ArrayList<>(elements.size());
            for (JsonNode element : elements) {
                // A bare empty XML model element is an empty concrete immutable instance.
                // XML's xsi:nil is a NullNode and follows the null branch below.
                if (isXml && element != null && element.isTextual() && element.asText().isEmpty()
                        && Immutable.class.isAssignableFrom(property.valueClass))
                    element = ctxt.getNodeFactory().objectNode();
                if (property.uniqueReferences && !VMFReferences.isIdNode(element)) {
                    if (isXml && element != null && element.isTextual() && element.asText().isEmpty())
                        element = ctxt.getNodeFactory().objectNode();
                    if (element == null || !element.isObject() || !Immutable.class.isAssignableFrom(
                            getVMFTypeClass(ctxt.getParser(), element, property.valueClass))) {
                        throw new IOException("Expected a target ID or an immutable definition in " + property.name);
                    }
                }
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
            private final boolean generatedMetadata;
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

                var generated = modelType && !methods.isEmpty()
                        ? VMFTypeUtils.generatedProperties(modelClass) : Optional.<List<Property>>empty();
                generatedMetadata = generated.isPresent();
                declarations = generated.map(BuilderPlan::byName)
                        .orElseGet(() -> modelType && !methods.isEmpty() ? declaredProperties(modelClass) : Map.of());
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
                    return byName(ReflectImpl.readProperties(reflect));
                } catch (ReflectiveOperationException | RuntimeException unavailable) {
                    return Map.of();
                }
            }

            private static Map<String, Property> byName(List<Property> declarations) {
                Map<String, Property> properties = new HashMap<>();
                for (Property declaration : declarations) {
                    properties.putIfAbsent(declaration.getName(), declaration);
                }
                return properties;
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
                        if (generatedMetadata) {
                            throw new NoSuchFieldException("Missing generated snapshot metadata for " + setter.method.getName());
                        }
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

        private static List<?> readExternalList(DeserializationContext ctxt, JsonNode value) throws IOException {
            if (value.isNull()) return null;
            if (!value.isArray()) throw new IOException("Expected an external list array");
            return ctxt.readTreeAsValue(value, List.class);
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
                    paramValue = (int) readInteger(value, true);
                } else if (paramType == Long.class|| paramType == long.class) {
                    paramValue = readInteger(value, false);
                } else if (paramType == Float.class || paramType == float.class) {
                    requireFloatingRange(value, true);
                    paramValue = ctxt.getParser().getCodec().treeToValue(value, paramType);
                } else if (paramType == Double.class|| paramType == double.class) {
                    requireFloatingRange(value, false);
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

        private static long readInteger(JsonNode value, boolean int32) throws IOException {
            String type = int32 ? "int" : "long";
            if (value.isIntegralNumber() && !(int32 ? value.canConvertToInt() : value.canConvertToLong())) {
                throw new IOException("Integer outside the range of " + type);
            }
            if (value.isTextual()) {
                // XML represents scalar leaves as text. Retain existing coercion for other
                // text forms, but do not let an overflowing integer silently default to zero.
                String scalar = value.textValue().trim();
                if (INTEGER_TEXT.matcher(scalar).matches()) {
                    try {
                        return int32 ? Integer.parseInt(scalar) : Long.parseLong(scalar);
                    } catch (NumberFormatException outOfRange) {
                        throw new IOException("Integer outside the range of " + type, outOfRange);
                    }
                }
            }
            return int32 ? value.asInt() : value.asLong();
        }

        private static void requireFloatingRange(JsonNode value, boolean single) throws IOException {
            boolean outOfRange = false;
            if (value instanceof NumericNode && !((NumericNode) value).isNaN()) {
                double rounded = single ? value.floatValue() : value.doubleValue();
                outOfRange = !Double.isFinite(rounded) || (rounded == 0d && value.decimalValue().signum() != 0);
            } else if (value.isTextual()) {
                // XML stores numbers as text. Recognize decimals while preserving canonical
                // non-finite strings and other caller-specific Jackson coercions.
                String literal = value.textValue().trim();
                if (DECIMAL_TEXT.matcher(literal).matches()) {
                    double rounded = single ? Float.parseFloat(literal) : Double.parseDouble(literal);
                    outOfRange = !Double.isFinite(rounded) || (rounded == 0d && hasNonzeroSignificand(literal));
                }
            }
            if (outOfRange) throw new IOException("Decimal outside the range of " + (single ? "float" : "double"));
        }

        private static boolean hasNonzeroSignificand(String literal) {
            for (int i = 0; i < literal.length(); i++) {
                char digit = literal.charAt(i);
                if (digit == 'e' || digit == 'E') return false;
                if (digit >= '1' && digit <= '9') return true;
            }
            return false;
        }

        private Class<?> getVMFTypeClass(JsonParser p, JsonNode node, Class<?> actualClass) throws JsonMappingException {
            String vmfTypeFieldName = "@vmf-type";

            var clsName = "com.fasterxml.jackson.dataformat.xml.deser.FromXmlParser";

            if(isInstanceOf(p, clsName) || (p.getCodec() != null && "XML".equals(p.getCodec().getFactory().getFormatName()))) {
                vmfTypeFieldName = "vmf-type";
            }

            JsonNode discriminator = node.get(vmfTypeFieldName);
            boolean readsRole = interfaceOnly && actualClass == _valueClass;
            if (discriminator == null) {
                if (readsRole) throw JsonMappingException.from(p, "A VMF role requires '" + vmfTypeFieldName + "'");
                return actualClass;
            }

            ModelRegistry configured = module.modelRegistry;
            ModelRegistry registry = configured == null && readsRole
                    ? VMFTypeUtils.interfaceOnlyModelRegistry(actualClass) : configured;
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
                if (actualClass == null || !isVMFObj(actualClass)) return Class.forName(type);
                Class<?> resolved = Class.forName(type, false, actualClass.getClassLoader());
                if (!actualClass.isAssignableFrom(resolved))
                    throw JsonMappingException.from(p, "VMF type '" + type + "' is not assignable to " + actualClass.getName());
                return resolved;
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
        private static final Set<String> NULLABLE_SCALAR_TYPES = Set.of(
                String.class.getName(), Boolean.class.getName(), Byte.class.getName(), Short.class.getName(),
                Integer.class.getName(), Long.class.getName(), Character.class.getName(),
                Float.class.getName(), Double.class.getName(), UUID.class.getName(), java.net.URI.class.getName());

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

            if (session != null && session.alreadyWroteImmutable(obj)) {
                gen.writeString(session.idOf(obj));
                return;
            }
            boolean root = gen.getOutputContext().inRoot();
            if (root) {
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
            if ((root && module.rootTypeDiscriminator)
                    || VMFTypeUtils.isTypeExtendsModelTypeInProps(obj, obj.vmf().reflect().type())) {

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
                    // Omitting a cleared scalar would reinstate the builder's non-null default on load.
                    if ((NULLABLE_SCALAR_TYPES.contains(p.getType().getName()) || p.getType().isEnumType())
                            && p.getDefault() != null) {
                        gen.writeFieldName(VMFTypeUtils.getFieldNameForProperty(p));
                        if (isInstanceOf(gen, "com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator")) {
                            XmlNullableValues.writeNull(gen);
                        } else {
                            gen.writeNull();
                        }
                    }
                    continue;
                }
                if (p.getType().isReferenceSetType()) {
                    var members = (eu.mihosoft.vmf.runtime.core.RefSet<?>) propValue;
                    if (session == null && VMFReferences.isReferenceProperty(p)
                            && members.stream().anyMatch(value -> !(value instanceof Immutable))) {
                        if (module.getReferenceMode() == ReferenceMode.OMIT) continue;
                        throw JsonMappingException.from(gen, "Mutable reference collection " + p.getName()
                                + " requires withReferences(true) or ReferenceMode.OMIT");
                    }
                    // Jackson's XML list wrapper and JSON arrays share the ordinary model-list representation.
                    List<Object> items = new ArrayList<>(members.size());
                    members.forEach(items::add);
                    propValue = items;
                }
                if (session != null && VMFReferences.isReferenceProperty(p)) {
                    gen.writeFieldName(VMFTypeUtils.getFieldNameForProperty(p));
                    writeModelValue(gen, p, VMFReferences.referenceValue(session, obj, p, propValue, gen));
                    continue;
                }
                // Contained targets and by-value immutable data use the ordinary object shape.
                if (VMFTypeUtils.isParentOfPropContainer(p)
                        || !p.getType().isModelType()
                        || propValue instanceof Immutable
                        || isImmutableModelList(p, obj)
                        || (VMFReferences.isReferenceProperty(p) && propValue instanceof List
                            && hasOnlyImmutableTargets((List<?>) propValue))) {
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
                        writeModelValue(gen, p, propValue);
                    }
                } else if (session == null && VMFReferences.isReferenceProperty(p) && hasMutableTarget(propValue)
                        && module.getReferenceMode() == ReferenceMode.REJECT) {
                    // A document without references cannot represent this edge; OMIT leaves it out.
                    throw JsonMappingException.from(gen, "Mutable reference " + p.getName()
                            + " requires withReferences(true) or ReferenceMode.OMIT");
                }
            }

            // End writing the object
            gen.writeEndObject();
        }

        private static boolean hasMutableTarget(Object value) {
            if (value instanceof List<?>) {
                for (Object item : (List<?>) value) {
                    if (item instanceof VObject && !(item instanceof Immutable)) return true;
                }
                return false;
            }
            return value instanceof VObject && !(value instanceof Immutable);
        }

        private static boolean hasOnlyImmutableTargets(List<?> values) {
            boolean hasTarget = false;
            for (Object value : values) {
                if (value == null) continue;
                if (!(value instanceof Immutable)) return false;
                hasTarget = true;
            }
            return hasTarget;
        }

        private static void writeModelValue(JsonGenerator gen, Property property, Object value) throws IOException {
            if (property.getType().isListType() && value instanceof List<?>
                    && isInstanceOf(gen, "com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator")
                    && ((List<?>) value).contains(null)) {
                XmlNullableValues.writeList((List<?>) value, gen);
            } else {
                gen.writeObject(value);
            }
        }

        // Loaded only when writing explicit nulls with the optional Jackson XML module.
        private static final class XmlNullableValues {
            static void writeList(List<?> values, JsonGenerator gen) throws IOException {
                var xml = (com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator) gen;
                xml.writeStartObject();
                xml.writeArrayFieldStart("item");
                for (Object value : values) {
                    if (value != null) {
                        xml.writeObject(value);
                    } else {
                        writeNull(xml);
                    }
                }
                xml.writeEndArray();
                xml.writeEndObject();
            }

            static void writeNull(JsonGenerator gen) throws IOException {
                var xml = (com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator) gen;
                var nil = com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator.Feature.WRITE_NULLS_AS_XSI_NIL;
                boolean enabled = xml.isEnabled(nil);
                try {
                    xml.enable(nil);
                    xml.writeNull();
                } finally {
                    xml.configure(nil, enabled);
                }
            }
        }

        private static boolean isImmutableModelList(eu.mihosoft.vmf.runtime.core.Property property, VObject owner)
                throws IOException {
            if (!property.getType().isListType() && !property.getType().isReferenceSetType()) return false;
            String element = property.getType().getElementTypeName().orElseThrow();
            try {
                return Immutable.class.isAssignableFrom(
                        Class.forName(element, false, owner.getClass().getClassLoader()));
            } catch (ClassNotFoundException exception) {
                throw new IOException("Unknown list element type " + element, exception);
            }
        }
    }
}
