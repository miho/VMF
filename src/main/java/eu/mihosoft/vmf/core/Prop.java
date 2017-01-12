package eu.mihosoft.vmf.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Created by miho on 06.01.2017.
 */
public class Prop {

    // method used to specify this property in the model interface (subpackage '.vmfmodel')
    private final Method getterMethod;

    // property name
    private final String name;

    // package name, e.g., 'eu.mihosoft.vmf.tutorial'
    private final String packageName;

    // type name without package, e.g. 'MyType'
    private final String typeName;

    // parent type, i.e., property owner
    private final ModelType parent;

    // containment info (container or contained property)
    private ContainmentInfo containmentInfo;

    //indicates whether this property is required (validation & constructors)
    private final boolean required;

    // indicates whether this property should be ignored for equals() code generation
    private final boolean ignoredForEquals;

    //type of the property, e.g., primitive or Collection
    private PropType propType;
    private ModelType type;

    private Prop(ModelType parent, Method getterMethod) {
        this.getterMethod = getterMethod;
        name = propertyNameFromGetter(getterMethod);
        this.parent = parent;

        Class<?> propClass = getterMethod.getReturnType();

        if (propClass.isPrimitive()) {
            propType = PropType.PRIMITIVE;
            typeName = propClass.getSimpleName();
            packageName = "";
        } else if (Collection.class.isAssignableFrom(propClass)) {
            propType = PropType.COLLECTION;

            ParameterizedType retType = (ParameterizedType) getterMethod
                    .getGenericReturnType();

            Class<?> containedClazz = (Class<?>) (retType
                    .getActualTypeArguments()[0]);

            if (!List.class.isAssignableFrom(propClass)) {
                throw new IllegalArgumentException(
                        "Currently only 'java.util.List<?>' is supported as Collection type.");
            } else {
                propType.collectionType = PropType.CollectionType.LIST;
                if (containedClazz.getPackage() == null) {
                    propType.collectionType.genericPackageName = "";
                } else {
                    propType.collectionType.genericPackageName = containedClazz.getPackage().getName();
                }

                propType.collectionType.genericTypeName = containedClazz.getSimpleName();
            }

            typeName = "List<" + parent.getModel().
                    convertModelTypeToDestination(containedClazz) + ">";
            packageName = "java.util";

        } else if (propClass.isArray()) {
            propType = PropType.COLLECTION;
            Class<?> containedClazz = propClass.getComponentType();
            typeName = "List<" + ModelType.primitiveToBoxedType(
                                    parent.getModel().
                                            convertModelTypeToDestination(containedClazz)) + ">";
            packageName = "java.util";

            propType.collectionType = PropType.CollectionType.LIST;
            if (containedClazz.getPackage() == null) {
                propType.collectionType.genericPackageName = "";
            } else {
                propType.collectionType.genericPackageName = containedClazz.getPackage().getName();
            }

            propType.collectionType.genericTypeName = containedClazz.getSimpleName();
        } else {
            propType = PropType.CLASS;
            typeName = propClass.getSimpleName();

            this.packageName = getParent().getModel().
                    convertModelPackageToDestination(propClass.getPackage().getName());
        }


        // check whether prop is required

        required = getterMethod.getAnnotation(Required.class) != null;
        ignoredForEquals = getterMethod.getAnnotation(IgnoreEquals.class) != null;
    }

    void initContainment() {

        System.out.println("init containment for " + getName());

        // containment

        Container container = getterMethod.getAnnotation(Container.class);
        Contains contained = getterMethod.getAnnotation(Contains.class);

        if (container != null) {
            // System.out.println("Container: " + container.opposite());
            Optional<Prop> opposite = parent.getModel().resolveOppositeOf(getParent(), container.opposite());

            if (opposite.isPresent()) {
                containmentInfo = ContainmentInfo.newInstance(
                        parent, opposite.get().getParent(), opposite.get(), ContainmentType.CONTAINER);
            } else {
                throw new RuntimeException(
                        "Specified opposite property '" + container.opposite() + "' cannot be found");
            }
        } else if (contained != null) {
            // System.out.println("Contained: " + contained.opposite());
            Optional<Prop> opposite = parent.getModel().resolveOppositeOf(getParent(), contained.opposite());

            if (opposite.isPresent()) {
                containmentInfo = ContainmentInfo.newInstance(
                        parent, opposite.get().getParent(), opposite.get(), ContainmentType.CONTAINED);
            } else {
                throw new RuntimeException(
                        "Specified opposite property '" + contained.opposite() + "' cannot be found");
            }
        } else {
            containmentInfo = ContainmentInfo.newInstance(null, null, null, ContainmentType.NONE);
        }
    }

    public static Prop newInstance(ModelType parent, Method getterMethod) {
        return new Prop(parent, getterMethod);
    }

    public String getTypeName() {
        return typeName;
    }

    public String getPackageName() {
        return packageName;
    }

    public ModelType getParent() {
        return parent;
    }

    public PropType getPropType() {
        return propType;
    }

    public String getName() {
        return name;
    }

    public ContainmentInfo getContainmentInfo() {
        return containmentInfo;
    }

    public boolean isRequired() {
        return required;
    }

    public String getGetterDeclaration() {
        return getTypeName() + " get" + getNameWithUpperCase()+ "()";
    }

    public String getSetterDeclaration() {
        return "void set" + getNameWithUpperCase()+ "(" + getTypeName() + " " + getName() + ")";
    }

    public boolean isContainmentProperty() {
        return getContainmentInfo().getContainmentType() != ContainmentType.NONE;
    }

    public String getNameWithUpperCase() {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    public boolean isIgnoredForEquals() {
        return ignoredForEquals;
    }

    public boolean isContained() {
        return getContainmentInfo().getContainmentType() == ContainmentType.CONTAINED;
    }

    public boolean isContainer() {
        return getContainmentInfo().getContainmentType() == ContainmentType.CONTAINER;
    }

    public boolean isCollectionType() {
        return getPropType() == PropType.COLLECTION;
    }

    public ModelType getType() {
        if(this.type==null){
            this.type = getParent().getModel().resolveType(getPackageName()+"."+getTypeName()).orElse(null);
        }

        return this.type;
    }


    static String propertyNameFromGetter(Method getterMethod) {
        String name = getterMethod.getName().substring("get".length());
        name = name.substring(0, 1).toLowerCase() + name.substring(1);
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Prop prop = (Prop) o;

        if (!name.equals(prop.name)) return false;
        if (!packageName.equals(prop.packageName)) return false;
        return typeName.equals(prop.typeName);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + packageName.hashCode();
        result = 31 * result + typeName.hashCode();
        return result;
    }
}
