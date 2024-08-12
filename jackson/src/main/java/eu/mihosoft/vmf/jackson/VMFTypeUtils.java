package eu.mihosoft.vmf.jackson;

import eu.mihosoft.vmf.runtime.core.*;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for VMF type related operations.
 */
public final class VMFTypeUtils {
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
        return Class.forName(clazz.getName() + "$Builder");
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

        try {
            Class<?> builderClass = VMFTypeUtils.getBuilderClass(cls);
            Object builder = builderClass.getDeclaredMethod("newInstance").invoke(null);

            // Build the final object
            Method buildMethod = builderClass.getDeclaredMethod("build");
            var obj = (VObject) buildMethod.invoke(builder);

            return obj.vmf().reflect().type();

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
        // get type name
        String typeName = type.getName();

        // get class object from name
        try {
            Class<?> cls = Class.forName(typeName);
            return cls.isEnum();
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }

    /**
     * Get the enum constants of a given type.
     * @param type the type to get the enum constants for
     * @return the enum constants of the given type
     */
    public static Object[] getEnumConstants(Type type) {
        // get type name
        String typeName = type.getName();

        // get class object from name
        try {
            Class<?> cls = Class.forName(typeName);
            return cls.getEnumConstants();
        } catch (ClassNotFoundException ex) {
            return new Object[0];
        }
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
     * Checks if the type is extended by another model type. Interface-only types are not considered.
     * @param model the model object
     * @param type the type to check
     * @return {@code true} if the type is extended by another model type, {@code false} otherwise
     */
    public static boolean isTypeExtendedByModelType(VObject model, eu.mihosoft.vmf.runtime.core.Type type) {

        var allTypes = model.vmf().reflect().allTypes();

        // now, check if type is a super type of any of the types
        for (var t : allTypes) {

            if(t == type) {
                continue;
            }

            if (t.isInterfaceOnly()) {
                continue;
            }

            if (t.superTypes().contains(type)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Check if the type extends a model type that is used as property type.
     * @param model the model object
     * @param type the type to check
     * @return {@code true} if the type extends a model type that is used as property type, {@code false} otherwise
     */
    public static boolean isTypeExtendsModelTypeInProps(VObject model, eu.mihosoft.vmf.runtime.core.Type type) {

        var allTypes = model.vmf().reflect().allTypes();

        var allTypesByName = new HashMap<String, Type>();
        allTypes.forEach(t -> allTypesByName.put(t.getName(), t));

        // receive all property types
        var allPropTypes = new HashSet<Type>();

        allTypes.forEach(t -> {

            if(t.isInterfaceOnly()) {
                return;
            }

            t.reflect().properties().forEach(p -> {
                allPropTypes.add(p.getType().isListType()?
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

            if (type.superTypes().contains(t)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Get all sub types of a given type (only works if model type).
     * @param type the type to get the sub types for
     * @return the list of sub types of the given type
     */
    public static List<Type> getSubTypes(Type type) {
         try {
             // get all types of the model
             List<Type> allTypes = type.reflect().allTypes();

             // get all sub types of the type
             return allTypes.stream().filter(t -> t.superTypes().contains(type)).collect(Collectors.toList());
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
        if(a.isPresent() || !p.getType().isModelType()) {
            var c = a.get().getValue();
            boolean contained =  c.contains("contained");
            return contained;
        }
        return false;
    }



}
