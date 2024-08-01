package eu.mihosoft.vmf.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;

/**
 * Generic builder module. This module provides a deserializer for VMF objects that have a builder. The deserializer
 * uses reflection to determine the builder methods and their corresponding fields. It is able to handle scalar values,
 * complex values, and collections. It also supports polymorphism by looking for a special field {@code @type} that
 * specifies the actual type of the object.
 */
public class GenericBuilderModule extends SimpleModule {

    private final Map<String, String> typeAliases = new HashMap<>();
    /**
     * Add a type alias to the module. This method is used to add a type alias that is used to determine the actual type
     * of an object during deserialization. The type alias is used to map a type name to a class name.
     *
     * <p>Example:
     * <pre>{@code
     * module.addTypeAlias("person", "eu.mihosoft.vmf.jackson.test.Person");
     * }</pre>
     * <p>This example adds a type alias {@code person} that maps to the class {@code eu.mihosoft.vmf.jackson.test.Person}.
     */
    public void addTypeAlias(String alias, String className) {
        typeAliases.put(alias, className);
    }

    /**
     * Returns the type aliases of the module.
     * @return the type aliases of the module
     */
    public Map<String, String> getTypeAliases() {
        return Collections.unmodifiableMap(typeAliases);
    }


    /**
     * Constructor. Creates a new instance of GenericBuilderModule.
     */
    public GenericBuilderModule() {
        setDeserializerModifier(new GenericBuilderDeserializerModifier(this));
    }

    /**
     * Add a type alias to the module. This method is used to add a type alias that is used to determine the actual type
     * of an object during deserialization. The type alias is used to map a type name to a class name.
     *
     * <p>Example:
     * <pre>{@code
     * module.addTypeAlias("person", "eu.mihosoft.vmf.jackson.test.Person");
     * }</pre>
     * <p>This example adds a type alias {@code person} that maps to the class {@code eu.mihosoft.vmf.jackson.test.Person}.
     */
    public GenericBuilderModule withTypeAlias(String alias, String className) {
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
    public GenericBuilderModule withTypeAliases(Map<String, String> typeAliases) {
        typeAliases.forEach(this::addTypeAlias);
        return this;
    }

    /**
     * Create a new instance of GenericBuilderModule.
     * @return a new instance of GenericBuilderModule
     */
    public static GenericBuilderModule newInstance() {
        var module = new GenericBuilderModule();
        return module;
    }

    /**
     * Generic builder deserializer modifier. This deserializer modifier is used to modify the deserializer of VMF objects
     * that have a builder. It checks if the object is a VMF object and has a builder. If so, it replaces the default
     * deserializer with a GenericBuilderDeserializer.
     */
    private static class GenericBuilderDeserializerModifier extends BeanDeserializerModifier {

        private final GenericBuilderModule module;

        public GenericBuilderDeserializerModifier(GenericBuilderModule module) {
            this.module = module;
        }

        @Override
        public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc, JsonDeserializer<?> deserializer) {
            if (isVMFObj(beanDesc.getBeanClass()) && hasBuilder(beanDesc.getBeanClass())) {
                return new GenericBuilderDeserializer<>(beanDesc.getBeanClass(), deserializer, module);
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

        /**
         * Check if the class is a VMF object.
         * @param clazz the class to check
         * @return {@code true} if the class is a VMF object, {@code false} otherwise
         */
        private boolean isVMFObj(Class<?> clazz) {
            // check if extends VObject
            return eu.mihosoft.vmf.runtime.core.VObject.class.isAssignableFrom(clazz);
        }
    }

    /**
     * Generic builder deserializer. This deserializer is used to deserialize VMF objects that have a builder. It uses
     * reflection to determine the builder methods and their corresponding fields. The deserializer is able to handle
     * scalar values, complex values, and collections. It also supports polymorphism by looking for a special field
     * {@code @type} that specifies the actual type of the object.
     * @param <T> the type of the object to deserialize
     */
    private static class GenericBuilderDeserializer<T> extends StdDeserializer<T> {

        private final JsonDeserializer<T> defaultDeserializer;

        private final GenericBuilderModule module;

        /**
         * Constructor. Creates a new instance of GenericBuilderDeserializer.
         * @param vc the value class
         * @param defaultDeserializer the default deserializer
         */
        public GenericBuilderDeserializer(Class<?> vc, JsonDeserializer<T> defaultDeserializer, GenericBuilderModule module) {
            super(vc);
            this.defaultDeserializer = defaultDeserializer;
            this.module = module;
        }

        @Override
        @SuppressWarnings("unchecked")
        public T deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            ObjectNode node = p.getCodec().readTree(p);

            Class<?> actualClass = _valueClass;

            // iterate over node fields and look for "@type" field to determine the actual type
            // do so and allow reiteration over the fields
            if(node.has("@type")) {
                String type = node.get("@type").asText();

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
                        String fieldName = method.getName().substring(4).toLowerCase();

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
                    String fieldName = entry.getKey().toLowerCase();
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
                                    // Deserialize the collection element
                                    Object elementValue = ctxt.readValue(
                                            element.traverse(ctxt.getParser().getCodec()),
                                            elementTypeClass);
                                    // Add the element to the collection
                                    collection.add(elementValue);
                                }
                                // Invoke the builder method
                                method.invoke(builder, collection);
                            } else {
                                // Handle null values
                                if (value.isNull()) {
                                    method.invoke(builder, (Object) null);
                                } else if (value.isValueNode()) {
                                    // Deserialize scalar value
                                    Object paramValue;
                                    if (paramType == String.class) {
                                        paramValue = value.asText();
                                    } else if (paramType == Integer.class||paramType == int.class) {
                                        paramValue = value.asInt();
                                    } else if (paramType == Long.class|| paramType == long.class) {
                                        paramValue = value.asLong();
                                    } else if (paramType == Double.class|| paramType == double.class) {
                                        paramValue = value.asDouble();
                                    } else if (paramType == Boolean.class|| paramType == boolean.class) {
                                        paramValue = value.asBoolean();
                                    } else {
                                        // Deserialize non-scalar value
                                        paramValue = ctxt.readValue(value.traverse(ctxt.getParser().getCodec()), paramType);
                                    }
                                    method.invoke(builder, paramValue);
                                } else {
                                    // Deserialize complex value
                                    Object paramValue = ctxt.readValue(value.traverse(ctxt.getParser().getCodec()), paramType);
                                    method.invoke(builder, paramValue);
                                }
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
        private Class<?> getBuilderClass(Class<?> clazz) throws ClassNotFoundException {
            return Class.forName(clazz.getName() + "$Builder");
        }
    }
}