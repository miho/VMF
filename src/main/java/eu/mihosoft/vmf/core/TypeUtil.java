package eu.mihosoft.vmf.core;

import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;

/**
 * Created by miho on 21.03.2017.
 */
public class TypeUtil {
    private TypeUtil() {
        throw new AssertionError("Don't instantiate me!");
    }

    /**
     * Returns the specified type as string. Collections are specified as array.
     * Thus, arrays and collection types other than lists are currently not supported.
     * @param model current model
     * @param cls class object to convert
     * @return the specified type as string
     */
    public static String getTypeAsString(Model model, Class<?> cls) {
        String packageName;
        String typeName;

        if (cls.isPrimitive()) {
            typeName = cls.getSimpleName();
            packageName = "";

        } else if (Collection.class.isAssignableFrom(cls)) {
           throw new RuntimeException(
                   "Collections can only be specified with array syntax, i.e., '<Type>[]'");
        } else if (cls.isArray()) {
            Class<?> containedClazz = cls.getComponentType();
            typeName = "VList<" + ModelType.primitiveToBoxedType(
                    model.convertModelTypeToDestination(containedClazz)) + ">";

            packageName = "eu.mihosoft.vcollections";

        } else {
            typeName = cls.getSimpleName();
            packageName = model.
                    convertModelPackageToDestination(cls.getPackage().getName());
        }

        if(!packageName.isEmpty()) {
            return packageName+"."+typeName;
        } else {
            return typeName;
        }
    }
}
