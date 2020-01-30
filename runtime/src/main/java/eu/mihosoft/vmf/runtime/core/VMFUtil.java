package eu.mihosoft.vmf.runtime.core;

import java.lang.reflect.InvocationTargetException;

import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;

/**
 * Utility interface that contains methods for instantiating VObjects
 * reflectively. This functionality hasn't been added to VMF yet. Expect this
 * API to vanish once VMF has an improved reflection API.
 */
public interface VMFUtil {

    /**
     * Returns the type if the specified object.
     * @param object object
     * @return type of the specified object
     */
    public static Type getType(VObject object) {
        try {

            String packageName = object.getClass().getPackage().getName();
            packageName = packageName.substring(0, packageName.length() - ".impl".length());
            String className = object.getClass().getSimpleName();
            className = className.substring(0, className.length() - "Impl".length());

            Type type = (Type) Class.forName(packageName + "." + className).getMethod("type").invoke(null);

            return type;
        } catch (IllegalAccessException 
        | IllegalArgumentException 
        | InvocationTargetException 
        | NoSuchMethodException
        | SecurityException 
        | ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return null;
    }

    /**
     * Creates a new instance of the type of the specified object.
     * @param object object 
     * @return new instance of the type of the specified object
     */
    public static VObject newInstance(VObject object) {
        return newInstance(getType(object));
    }

    /**
     * Creates a new instance of the specified type.
     * @param type type to instantiate
     * @return new instance of the specified type
     */
    public static VObject newInstance(Type type) {
        return newInstance(type.getName());
    }

    /**
     * Creates a new instance of the specified type.
     * @param typeName type to instantiate
     * @return new instance of the specified type
     */
    public static VObject newInstance(String typeName) {

        try {
            return (VObject) Class.forName(typeName).getMethod("newInstance").invoke(null);
        } catch (IllegalAccessException 
        | IllegalArgumentException 
        | InvocationTargetException 
        | NoSuchMethodException
        | SecurityException 
        | ClassNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return null;

    }
}