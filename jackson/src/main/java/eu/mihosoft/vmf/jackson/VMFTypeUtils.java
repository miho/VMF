package eu.mihosoft.vmf.jackson;

import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.lang.reflect.Method;

public final class VMFTypeUtils {
    private VMFTypeUtils() {
        throw new AssertionError("Don't instantiate me");
    }

    public static boolean isVMFType(Class<?> type) {
        return eu.mihosoft.vmf.runtime.core.VObject.class.isAssignableFrom(type);
    }

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

    public static Type forClass(String clsName) {
        try {
            return forClass(Class.forName(clsName));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Type forClass(Class<?> cls) {
        // check if VObject
        if (!VObject.class.isAssignableFrom(cls)) {

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

    // get enum constants
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

}
