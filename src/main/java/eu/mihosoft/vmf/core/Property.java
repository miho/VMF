package eu.mihosoft.vmf.core;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;

public class Property {
    String nameCC;
    boolean required;
    String type;
    String containedType;
    String collectionType;
    String packageName;
    boolean modelType;
    boolean collection;
    Type parent;

    public void init(Type parent, Method getterMethod) {
        this.parent = parent;
        nameCC = Type.propertyNameFromGetter(getterMethod);
        collection = Collection.class.isAssignableFrom(getterMethod.getReturnType());

        if (collection) {
            ParameterizedType retType = (ParameterizedType) getterMethod
                    .getGenericReturnType();
            Class<?> containedClzz = (Class<?>) (retType
                    .getActualTypeArguments()[0]);
            collectionType = containedClzz.getSimpleName();
        }
        if (getterMethod.getReturnType().getPackage() != null)
            packageName = getterMethod.getReturnType().getPackage().getName();
        else
            packageName = "java.lang";

        required = (getterMethod.getDeclaredAnnotation(Required.class) != null);

    }

    public String getContainedType() {
        return containedType;
    }

    public String getContainedTypeFQN() {
        return parent.getPackageName() + "." + containedType;
    }

    public Type getContainedTypeDescr() {
        return parent.getModel().resolve(getContainedTypeFQN());
    }

    public Type getParent() {
        return parent;
    }

    /**
     * @return String for import statement.
     */
    public String getImport() {
        return packageName + "." + type;
    }

    /**
     * @return fully qualified type descriptor
     */
    public String getFQN() {
        return packageName + "." + getDeclaration();
    }

    /**
     * @return type declaration, assuming the type has been imported
     */
    public String getDeclaration() {
        return type + (collection ? "<" + (collectionType) + ">" : "");
    }

    public String getNameCc() {
        return nameCC;
    }

    public boolean isRequired() {
        return required;
    }

    public String getType() {
        return type;
    }

    public String getPackageName() {
        return packageName;
    }

    public boolean isModelType() {
        return modelType;
    }

    public boolean isCollection() {
        return collection;
    }

    public void setModelType(boolean value) {
        this.modelType = value;
    }

    public String getCollectionType() {
        return collectionType;
    }
}