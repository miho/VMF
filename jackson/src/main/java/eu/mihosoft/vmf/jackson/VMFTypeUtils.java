package eu.mihosoft.vmf.jackson;

import eu.mihosoft.vmf.runtime.core.*;
import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Utility class for VMF type related operations.
 */
public final class VMFTypeUtils {
    private static final String WIRE_TYPE_KEY = "vmf:wire:type";
    private static final ClassValue<Optional<SnapshotDeclarations>> SNAPSHOT_DECLARATIONS = new ClassValue<>() {
        @Override
        protected Optional<SnapshotDeclarations> computeValue(Class<?> modelClass) {
            return ReflectImpl.generatedDeclaration(modelClass).map(SnapshotDeclarations::new);
        }
    };

    private static final ClassValue<ModelRegistry> ROLE_MODEL_REGISTRIES = new ClassValue<>() {
        @Override
        protected ModelRegistry computeValue(Class<?> role) {
            String packageName = role.getPackageName();
            String name = packageName.substring(packageName.lastIndexOf('.') + 1);
            String catalogName = packageName + "." + name.substring(0, 1).toUpperCase() + name.substring(1)
                    + "Model__VMF_API";
            try {
                Class<?> catalog = Class.forName(catalogName, false, role.getClassLoader());
                var exported = (List<?>) catalog.getMethod("getTypes").invoke(null);
                var registry = new ModelRegistry(List.of(exported.stream().map(Type.class::cast).collect(Collectors.toList())));
                if (!registry.findJavaType(role.getName()).filter(candidate -> candidate == role).isPresent()) {
                    throw new IllegalArgumentException("The generated catalog does not contain " + role.getName());
                }
                return registry;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("Cannot read the VMF catalog of " + role.getName(), failure);
            }
        }
    };

    static ModelRegistry interfaceOnlyModelRegistry(Class<?> role) {
        return ROLE_MODEL_REGISTRIES.get(role);
    }

    private static final class SnapshotDeclarations {
        private final Reflect reflection;
        private final List<Property> properties;
        private final List<Annotation> annotations;

        private SnapshotDeclarations(Reflect reflection) {
            this.reflection = reflection;
            properties = List.copyOf(ReflectImpl.readProperties(reflection));
            annotations = List.copyOf(reflection.annotations());
        }
    }

    static Optional<List<Property>> generatedProperties(Class<?> modelClass) {
        return SNAPSHOT_DECLARATIONS.get(modelClass).map(declarations -> declarations.properties);
    }

    static Optional<Type> interfaceOnlyDeclaration(Class<?> modelClass) {
        if (!modelClass.isInterface() || !VObject.class.isAssignableFrom(modelClass)
                || Mutable.class.isAssignableFrom(modelClass) || Immutable.class.isAssignableFrom(modelClass)) {
            return Optional.empty();
        }
        try {
            Method declaration = modelClass.getDeclaredMethod("type");
            if (!Modifier.isStatic(declaration.getModifiers()) || declaration.getReturnType() != Type.class) {
                return Optional.empty();
            }
            var type = (Type) declaration.invoke(null);
            return type != null && type.isModelType() && type.isInterfaceOnly()
                    && modelClass.getName().equals(type.getName()) ? Optional.of(type) : Optional.empty();
        } catch (NoSuchMethodException absent) {
            return Optional.empty();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot read the VMF role " + modelClass.getName(), failure);
        }
    }

    private static Optional<SnapshotDeclarations> snapshotDeclarations(Type type, ClassLoader loader) {
        try {
            return SNAPSHOT_DECLARATIONS.get(Class.forName(type.getName(), false, loader));
        } catch (ClassNotFoundException notVisible) {
            return Optional.empty();
        }
    }

    private VMFTypeUtils() {
        throw new AssertionError("Don't instantiate me");
    }

    /**
     * Check if the given class is a VMF type.
     * @param type the class to check
     * @return {@code true} if the given class is a VMF type, {@code false} otherwise
     */
    public static boolean isVMFType(Class<?> type) {
        return eu.mihosoft.vmf.runtime.core.VObject.class.isAssignableFrom(type);
    }

    /**
     * Check if the given object is an instance of a VMF type.
     * @param obj the object to check
     * @return {@code true} if the given object is an instance of a VMF type, {@code false} otherwise
     */
    public static boolean isVMFType(Object obj) {
        return isVMFType(obj.getClass());
    }

    /**
     * Get the builder class for a given class.
     * @param clazz the class object
     * @return the builder class object of the given class
     * @throws ClassNotFoundException if the builder class cannot be found
     */
    public static Class<?> getBuilderClass(Class<?> clazz) throws ClassNotFoundException {
        return Class.forName(clazz.getName() + "$Builder", true, clazz.getClassLoader());
    }

    /**
     * Returns the VMF type object for a given class name.
     * @param clsName the class name
     * @return the VMF type object for the given class name
     */
    public static Type forClass(String clsName) {
        try {
            return forClass(Class.forName(clsName));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the VMF type object for a given class.
     * @param cls the class object
     * @return the VMF type object for the given class
     */
    public static Type forClass(Class<?> cls) {

        if(Collection.class.isAssignableFrom(cls)) {
            throw new RuntimeException("Collection type is not supported");
        }

        // check if VObject
        if (!VObject.class.isAssignableFrom(cls)) {

            boolean isModelType = true; // here, we currently only have the case it's a model type

            // check if tClass implements Mutable or Immutable interfaces:
            var isMutable   = Mutable.class.isAssignableFrom(cls);
            var isImmutable = Immutable.class.isAssignableFrom(cls);

            boolean isInterfaceOnlyType = isModelType && !isMutable && !isImmutable;

            return Type.newInstance(false, false, isInterfaceOnlyType, cls.getName(), cls);
        }


        var role = interfaceOnlyDeclaration(cls);
        if (role.isPresent()) return role.get();

        try {
            Class<?> builderClass = VMFTypeUtils.getBuilderClass(cls);

            // check if builder has newInstance method
            Method newInstanceMethod = null;

            try {
                newInstanceMethod = builderClass.getDeclaredMethod("newInstance");
            } catch (NoSuchMethodException e) {
                // ignore
            }

            // if not there, try a different approach
            if(newInstanceMethod == null) {
                //
                // we check type() method on original class
                Method typeMethod = null;

                try {
                    typeMethod = cls.getDeclaredMethod("type");
                } catch (NoSuchMethodException e) {
                    // ignore
                }

                if(typeMethod == null) {
                    throw new RuntimeException("Could not find newInstance method in builder class and type method in original class.");
                }

                // get the type object
                return (Type) typeMethod.invoke(null);
            } else {

                Object builder = builderClass.getDeclaredMethod("newInstance").invoke(null);

                // Build the final object
                Method buildMethod = builderClass.getDeclaredMethod("build");
                var obj = (VObject) buildMethod.invoke(builder);

                return obj.vmf().reflect().type();
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Indicates whether the given type is an enum.
     * @param type the type to check
     * @return {@code true} if the given type is an enum, {@code false} otherwise
     */
    public static boolean isEnum(Type type) {
        return type.isEnumType();
    }

    /**
     * Get the enum constants of a given type.
     * @param type the type to get the enum constants for
     * @return the enum constants of the given type
     */
    public static Object[] getEnumConstants(Type type) {
        return type.getEnumConstants();
    }

    /**
     * Get the field name for a property. This method is used to determine the field name of a property. It checks for
     * the presence of the `@vmf:jackson:rename` annotation and uses the value of the annotation as the field name if
     * present.
     * @param p the property to get the field name for
     * @return the field name of the property
     */
    public static String getFieldNameForProperty(Property p) {
        var a = p.annotationByKey("vmf:jackson:rename");
        if(a.isPresent()) {
            return a.get().getValue();
        }
        return p.getName();
    }

    /**
     * Reads portable wire aliases declared on the model's concrete types.
     * @param modelType a generated type from the model
     * @return aliases mapped to generated Java type names
     * @throws IllegalArgumentException if an alias is blank, ambiguous or conflicts with an existing type name
     */
    public static Map<String, String> modelWireTypeAliases(Class<? extends VObject> modelType) {
        var types = interfaceOnlyDeclaration(modelType).isPresent()
                ? interfaceOnlyModelRegistry(modelType).allTypes()
                : SNAPSHOT_DECLARATIONS.get(modelType)
                        .map(declarations -> declarations.reflection.allTypes())
                        .orElseGet(() -> forClass(modelType).reflect().allTypes());
        var typeNames = types.stream().map(Type::getName).collect(Collectors.toSet());
        var aliases = new LinkedHashMap<String, String>();
        for (Type type : types) {
            if (type.isInterfaceOnly()) {
                continue;
            }
            var annotations = snapshotDeclarations(type, modelType.getClassLoader())
                    .map(declarations -> declarations.annotations)
                    .orElseGet(() -> type.reflect().annotations());
            var annotation = annotations.stream().filter(value -> WIRE_TYPE_KEY.equals(value.getKey())).findFirst();
            if (annotation.isEmpty()) {
                continue;
            }

            String alias = annotation.get().getValue();
            String name = type.getName();
            if (alias.isBlank() || (typeNames.contains(alias) && !alias.equals(name))) {
                throw new IllegalArgumentException("Invalid VMF wire type alias '" + alias + "' for " + name);
            }
            String previous = aliases.putIfAbsent(alias, name);
            if (previous != null && !previous.equals(name)) {
                throw new IllegalArgumentException("VMF wire type alias '" + alias + "' is shared by "
                        + previous + " and " + name);
            }
        }
        return Map.copyOf(aliases);
    }

    static Map<String, String> modelWireTypeAliases(Class<? extends VObject> modelType,
                                                    Map<String, String> configuredAliases) {
        var aliases = modelWireTypeAliases(modelType);
        for (var alias : aliases.entrySet()) {
            for (var existing : configuredAliases.entrySet()) {
                boolean sameAlias = alias.getKey().equals(existing.getKey());
                boolean sameType = alias.getValue().equals(existing.getValue());
                if (sameAlias != sameType) {
                    throw new IllegalArgumentException("VMF wire type alias '" + alias.getKey()
                            + "' for " + alias.getValue() + " conflicts with a configured alias");
                }
            }
        }
        return aliases;
    }

    /**
     * Checks if the type is extended by another model type. Interface-only types are not considered.
     * @param model the model object
     * @param type the type to check
     * @return {@code true} if the type is extended by another model type, {@code false} otherwise
     */
    public static boolean isTypeExtendedByModelType(VObject model, eu.mihosoft.vmf.runtime.core.Type type) {

        return model.vmf().reflect().allTypes().stream()
                .anyMatch(t -> !t.isInterfaceOnly() && t.isModelSubtypeOf(type));
    }

    /**
     * Checks whether the type participates in a polymorphic model property,
     * either as a subtype or as its concrete declared base type.
     * @param model the model object
     * @param type the type to check
     * @return {@code true} if a discriminator is needed, {@code false} otherwise
     */
    public static boolean isTypeExtendsModelTypeInProps(VObject model, eu.mihosoft.vmf.runtime.core.Type type) {

        var allTypes = model.vmf().reflect().allTypes();

        var allTypesByName = new HashMap<String, Type>();
        allTypes.forEach(t -> allTypesByName.put(t.getName(), t));

        // receive all property types
        var allPropTypes = new HashSet<Type>();
        ClassLoader loader = model.getClass().getClassLoader();

        allTypes.forEach(t -> {

            if(t.isInterfaceOnly()) {
                return;
            }

            var properties = snapshotDeclarations(t, loader)
                    .map(declarations -> declarations.properties)
                    .orElseGet(() -> t.reflect().properties());
            properties.forEach(p -> {
                allPropTypes.add(p.getType().isListType() || p.getType().isReferenceSetType() ?
                        allTypesByName.get(p.getType().getElementTypeName().get())
                        : p.getType());
            });
        });

        // check if any interface only type is not extended by any other type
        for (var t : allPropTypes) {

            if(t == null) {
                continue;
            }

            if (t.isInterfaceOnly() && !isTypeExtendedByModelType(model, t)) {
                throw new RuntimeException(
                        "Interface-only type '"
                                + t.getName()
                                + "' is not extended by any other type. Model cannot be de-/serialized since" +
                                " interface-only types cannot be instantiated.");
            }
        }

        // now, check if type extends any of the types
        for (var t : allPropTypes) {

            if(t == null) {
                continue;
            }

            if (type.isModelSubtypeOf(t)) {
                return true;
            }
            // A concrete declared base type is also a polymorphic choice in
            // the generated schema, and needs the same discriminator as its subtypes.
            if (type.getName().equals(t.getName()) && isTypeExtendedByModelType(model, type)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get all subtypes of a given type (only works if model type).
     * @param type the type to get the subtypes for
     * @return the list of subtypes of the given type
     */
    public static List<Type> getSubTypes(Type type) {
         try {
             // get all types of the model
             List<Type> allTypes = type.reflect().allTypes();

             // get all sub types of the type
             return allTypes.stream().filter(t -> t.isModelSubtypeOf(type)).collect(Collectors.toList());
         } catch (Exception e) {
             return List.of(); // empty list if not found
         }
    }

    /**
     * Check if the parent of this property is a container.
     * @param p the property to check
     * @return {@code true} if the parent of this property is a container, {@code false} otherwise
     */
    public static boolean isParentOfPropContainer(Property p) {
        var a = p.annotationByKey("vmf:property:containment-info");
        // VMF emits this annotation on every property (value "none", "contained",
        // "contained:<opposite>" or "container:<opposite>"). Guard the Optional so we
        // never call get() on an absent value. A "contained" prop holds children that
        // must be serialized; a "container" back-reference must not.
        return a.map(annotation -> {
            String value = annotation.getValue();
            return "contained".equals(value) || value.startsWith("contained:");
        }).orElse(false);
    }



}
