package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import eu.mihosoft.vmf.runtime.core.Property;
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
 * mapper.registerModule(new VMFJacksonModule()
 *          .withTypeAlias("person", Person.class.getName())
 *          .withTypeAlias("employee", Employee.class.getName())
 *       );
 * }</pre>
 * </p>
 * <p>This example registers the module with the type aliases {@code person} and {@code employee} that map to the classes
 * {@code Person} and {@code Employee} respectively.</p>
 *
 */
public class VMFJacksonModule extends SimpleModule {

    public enum RUNTIME_TYPE {
        RELEASE, EXPERIMENTAL
    }

    private final Map<String, String> typeAliases = new HashMap<>();
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
     * Constructor. Creates a new instance of VMFJacksonModule.
     */
    private VMFJacksonModule() {
        setDeserializerModifier(new GenericBuilderDeserializerModifier(this));
        setSerializerModifier(new BeanSerializerModifier() {
            @Override
            public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {

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
     * Add a type alias to the module. This method is used to add a type alias that is used to determine the actual type
     * of an object during serialization/deserialization. The type alias is used to map a type name to a class name.
     *
     * <p>Example:
     * <pre>{@code
     * module.addTypeAlias("person", "eu.mihosoft.vmf.jackson.test.Person");
     * }</pre>
     * <p>This example adds a type alias {@code person} that maps to the class {@code eu.mihosoft.vmf.jackson.test.Person}.
     */
    public VMFJacksonModule withTypeAlias(String alias, String className) {
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
    public VMFJacksonModule withTypeAliases(Map<String, String> typeAliases) {
        typeAliases.forEach(this::addTypeAlias);
        return this;
    }

    /**
     * Create a new instance of VMFJacksonModule.
     * @return a new instance of VMFJacksonModule
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
     * Create a new instance of VMFJacksonModule.
     * @return a new instance of VMFJacksonModule
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
            if (isVMFObj(beanDesc.getBeanClass()) && hasBuilder(beanDesc.getBeanClass())) {

                // Check if we have XML deserializer by examining the deserializer or its config
                boolean isXml = false;
                if (deserializer != null) {
                    isXml = deserializer.getClass().getName().toLowerCase().contains("xml");
                }

                return new GenericBuilderDeserializer<>(beanDesc.getBeanClass(), deserializer, module, isXml);
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

        private final JsonDeserializer<T> defaultDeserializer;

        private final VMFJacksonModule module;
        private final boolean isXml;

        /**
         * Constructor. Creates a new instance of GenericBuilderDeserializer.
         * @param vc the value class
         * @param defaultDeserializer the default deserializer
         */
        public GenericBuilderDeserializer(Class<?> vc, JsonDeserializer<T> defaultDeserializer,
                                          VMFJacksonModule module, boolean isXml) {
            super(vc);
            this.defaultDeserializer = defaultDeserializer;
            this.module = module;
            this.isXml = isXml;

        }


        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {

            JsonNode node = p.readValueAsTree();

            return deserializeNode(p, ctxt, node);

        }

        private T deserializeNode(JsonParser p, DeserializationContext ctxt, JsonNode node) throws IOException {
            Class<?> actualClass = _valueClass;

            // iterate over node fields and look for "@vmf-type" field to determine the actual type
            // do so and allow reiteration over the fields

            actualClass = getVMFTypeClass(p, node, actualClass);

            try {

                // Get the builder class and instance
                Class<?> builderClass = getBuilderClass(actualClass);
                Object builder = builderClass.getDeclaredMethod("newInstance").invoke(null);

                // Get all methods from the builder
                Method[] methods = builderClass.getDeclaredMethods();

                // Map of field names to builder methods
                Map<String, Method> methodMap = new HashMap<>();
                for (Method method : methods) {
                    if (method.getName().startsWith("with")) {

                        String fieldName = getFieldNameFromMethod(actualClass, method);

                        try {

                            // if more than one parameter, skip the method, usually this indicates a collection with
                            // builder elements
                            if (method.getParameterCount() != 1) {
                                continue;
                            }

                            // if our parameter is an array, skip the method
                            if (method.getParameterTypes()[0].isArray()) {
                                continue;
                            }

                            // if our parameter extends the base VMF Builder interface, skip the method
                            if (eu.mihosoft.vmf.runtime.core.Builder.class.isAssignableFrom(
                                    getClassFromType(method.getParameterTypes()[0]))) {
                                continue;
                            }

                        } catch (Exception e) {
                            System.err.println("Error checking parameter type for method " + method);
                            e.printStackTrace(System.err);
                        }

                        // we accept the method
                        methodMap.put(fieldName, method);

                    }
                }

                // Iterate over JSON fields and invoke corresponding builder methods
                node.fields().forEachRemaining(entry -> {
                    String fieldName = entry.getKey();
                    
                    JsonNode value = entry.getValue();

                    // check if we have a method for the field
                    if (methodMap.containsKey(fieldName)) {
                        Method method = methodMap.get(fieldName);
                        try {
                            // Determine the parameter type
                            Class<?> paramType = method.getParameterTypes()[0];

                            if (Collection.class.isAssignableFrom(paramType)) {
                                // Get the collection type
                                var genericParamType = method.getGenericParameterTypes()[0];
                                Class<?> elementTypeClass = getClassFromType(getElementType(genericParamType));

                                // Create a new collection (we assume it's a List)
                                Collection<Object> collection = (Collection<Object>) new ArrayList<>();
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
                                method.invoke(builder, collection);
                            } else {
                                var paramValue = deserializeField(ctxt, value, paramType);
                                method.invoke(builder, paramValue);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    } else {
                        // System.out.println("No method found for field: " + fieldName);
                    }
                });

                // Build the final object
                Method buildMethod = builderClass.getDeclaredMethod("build");
                return (T) buildMethod.invoke(builder);
            } catch (Exception e) {
                throw new IOException("Error deserializing object", e);
            }
        }

        private String getFieldNameFromMethod(Class<?> actualClass, Method method) {
            String fieldName = method.getName().substring(4,5).toLowerCase()
                    + method.getName().substring(5);

            if(isVMFObj(actualClass)) {
                try {
                    Class<?> builderClass = getBuilderClass(actualClass);
                    Object builder = builderClass.getDeclaredMethod("newInstance").invoke(null);

                    // Build the final object
                    Method buildMethod = builderClass.getDeclaredMethod("build");
                    var obj = (VObject) buildMethod.invoke(builder);

                    var prop = obj.vmf().reflect().propertyByName(fieldName).get();

                    var a = prop.annotationByKey("vmf:jackson:rename");
                    if(a.isPresent()) {
                        fieldName = a.get().getValue();
                    }
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException(e);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                } catch (NoSuchMethodException e) {
                    throw new RuntimeException(e);
                }
            }

            return fieldName;
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

        private Class<?> getVMFTypeClass(JsonParser p, JsonNode node, Class<?> actualClass) {
            String vmfTypeFieldName = "@vmf-type";

            var clsName = "com.fasterxml.jackson.dataformat.xml.deser.FromXmlParser";

            if(isInstanceOf(p, clsName)) {
                vmfTypeFieldName = "vmf-type";
            }

            if(node.has(vmfTypeFieldName)) {
                String type = node.get(vmfTypeFieldName).asText();

                // check if we have a type alias
                if(module.getTypeAliases().containsKey(type)) {
                    type = module.getTypeAliases().get(type);
                }

                // get the actual class
                try {
                    actualClass = Class.forName(type);
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }
            return actualClass;
        }

        /**
         * Get the element type of a parameterized type.
         * @param type the parameterized type
         * @return the element type or {@code null} if the element type cannot be determined
         */
        private static Type getElementType(Type type) {
            if (type instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) type;
                Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
                if (actualTypeArguments.length > 0) {
                    return actualTypeArguments[0]; // Return the first type argument
                }
            }
            return null;
        }

        /**
         * Get the class from a Type object (if possible). This method is used to determine the actual class of a
         * parameterized type.
         * @param type the Type object
         * @return the Class object or {@code null} if the class cannot be determined
         */
        private static Class<?> getClassFromType(Type type) {

            if (type instanceof Class<?>) {
                return (Class<?>) type;
            } else if (type instanceof ParameterizedType) {
                ParameterizedType parameterizedType = (ParameterizedType) type;
                Type rawType = parameterizedType.getRawType();
                if (rawType instanceof Class<?>) {
                    return (Class<?>) rawType;
                }
            } else if (type instanceof TypeVariable<?>) {
                TypeVariable<?> typeVariable = (TypeVariable<?>) type;
                // Handle TypeVariable if needed, this part depends on our specific requirements
                // For example, we might want to get bounds or other relevant information
            } else if (type instanceof WildcardType) {
                WildcardType wildcardType = (WildcardType) type;
                // Handle WildcardType if needed, this part depends on our specific requirements
                // For example, we might want to get upper or lower bounds
            }
            return null;
        }

        /**
         * Get the builder class for a given class.
         * @param clazz the class object
         * @return the builder class object of the given class
         * @throws ClassNotFoundException if the builder class cannot be found
         */
        static Class<?> getBuilderClass(Class<?> clazz) throws ClassNotFoundException {
            return Class.forName(clazz.getName() + "$Builder");
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

            // For each property, serialize the value
            obj.vmf().reflect().properties().forEach(p -> {
                try {
                    Object propValue = p.get();
                    // if property is set and holds contained value or is external or immutable, serialize it
                    if (propValue != null && (
                            VMFTypeUtils.isParentOfPropContainer(p)
                                    || !p.getType().isModelType()
                                    || propValue instanceof eu.mihosoft.vmf.runtime.core.Immutable)) {
                        gen.writeFieldName(VMFTypeUtils.getFieldNameForProperty(p));
                        gen.writeObject(propValue);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            // End writing the object
            gen.writeEndObject();
        }
    }
}
