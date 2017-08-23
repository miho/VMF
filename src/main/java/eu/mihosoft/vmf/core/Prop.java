package eu.mihosoft.vmf.core;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class Prop {

    // method used to specify this property in the model interface (subpackage '.vmfmodel')
    private final Method getterMethod;

    // property name
    private final String name;

    // package name, e.g., 'eu.mihosoft.vmf.tutorial'
    private String packageName;

    // type name without package, e.g. 'MyType'
    private String typeName;

    // parent type, i.e., property owner
    private final ModelType parent;

    // containment info (container or contained property)
    private ContainmentInfo containmentInfo;

    // sync info
    private SyncInfo syncInfo;

    //indicates whether this property is required (validation & constructors)
    private boolean required;

    // indicates whether this property should be ignored for equals() code generation
    private boolean ignoredForEquals;

    // getter prefix (get or is)
    private String getterPrefix;

    //type of the property, e.g., primitive or Collection
    private PropType propType;
    private ModelType type;

    private String genericPackageName;
    private String genericTypeName;
    private CollectionType collectionType;
    private boolean getterOnly;

    private int propId;

    private Prop(ModelType parent, Method getterMethod) {
        this.getterMethod = getterMethod;
        name = propertyNameFromGetter(getterMethod);
        this.parent = parent;

        initType(parent.getModel(), getterMethod);
    }

    void initType(Model m, Method getterMethod) {

        Class<?> propClass = getterMethod.getReturnType();

        if (propClass.isPrimitive()) {
            propType = PropType.PRIMITIVE;
            typeName = propClass.getSimpleName();
            packageName = "";

        } else if (Collection.class.isAssignableFrom(propClass)) {
            propType = PropType.COLLECTION;

            ParameterizedType retType = null;

            if (getterMethod.getGenericReturnType() != null) {
                if(getterMethod.getGenericReturnType() instanceof ParameterizedType) {
                    retType = (ParameterizedType) getterMethod
                            .getGenericReturnType();
                }
            }

            Class<?> containedClazz;

            if (retType != null) {
                containedClazz = (Class<?>) (retType
                        .getActualTypeArguments()[0]);
            } else {
                containedClazz = Object.class;
            }

            if (!List.class.isAssignableFrom(propClass)) {
                throw new IllegalArgumentException(
                        "Currently only 'java.util.List<?>' is supported as Collection type.");
            } else {
                collectionType = CollectionType.LIST;
                if (containedClazz.getPackage() == null) {
                    genericPackageName = "";
                } else {
                    genericPackageName = m.
                            convertModelPackageToDestination(
                                    containedClazz.getPackage().getName());
                }

                genericTypeName = containedClazz.getSimpleName();
            }

            typeName = "VList<" + m.
                    convertModelTypeToDestination(containedClazz) + ">";
            packageName = "eu.mihosoft.vcollections";

        } else if (propClass.isArray()) {
            propType = PropType.COLLECTION;
            Class<?> containedClazz = propClass.getComponentType();
            typeName = "VList<" + ModelType.primitiveToBoxedType(
                    m.
                            convertModelTypeToDestination(containedClazz)) + ">";
            // System.out.println("TYPENAME: " + typeName);

            packageName = "eu.mihosoft.vcollections";

            collectionType = CollectionType.LIST;
            if (containedClazz.getPackage() == null) {
                genericPackageName = "";
            } else {
                genericPackageName = m.
                        convertModelPackageToDestination(containedClazz.getPackage().getName());
            }

//            System.out.println("CONTAINED_TYPE: " + containedClazz.getSimpleName());

            genericTypeName = containedClazz.getSimpleName();
        } else {
            propType = PropType.CLASS;
            typeName = propClass.getSimpleName();

            this.packageName = getParent().getModel().
                    convertModelPackageToDestination(propClass.getPackage().getName());
        }

        getterPrefix = computeGetterPrefix(getterMethod);

        // check whether prop is required
        required = getterMethod.getAnnotation(Required.class) != null;
        ignoredForEquals = getterMethod.getAnnotation(IgnoreEquals.class) != null;

        getterOnly = getterMethod.getAnnotation(GetterOnly.class) != null;
    }

    void setPropId(int propId) {
        this.propId = propId;
    }

    public int getPropId() {
        return this.propId;
    }

    void initContainment() {

        // System.out.println("init containment for " + getName());

        // containment
        Container container = getterMethod.getAnnotation(Container.class);
        Contains contained = getterMethod.getAnnotation(Contains.class);

        if (container != null) {

            String oppositeOfGetContainerProperty = container.opposite();

            Optional<Prop> opposite = parent.getModel().resolveOppositeOf(getParent(), oppositeOfGetContainerProperty);

            // if opposite can't be found, try with full name
            if (!opposite.isPresent()) {
                if (isCollectionType()) {
                    oppositeOfGetContainerProperty = getGenericTypeName() + "." + oppositeOfGetContainerProperty;
                } else {
                    oppositeOfGetContainerProperty = getTypeName() + "." + oppositeOfGetContainerProperty;
                }

                opposite = parent.getModel().resolveOppositeOf(getParent(), oppositeOfGetContainerProperty);
            }


            if (opposite.isPresent()) {
                this.containmentInfo = ContainmentInfo.newInstance(
                        parent, this, opposite.get().getParent(), opposite.get(), ContainmentType.CONTAINER);
            } else {
                throw new RuntimeException(
                        "Specified opposite property '" + oppositeOfGetContainerProperty + "' cannot be found");
            }
        } else if (contained != null) {
            // System.out.println("Contained: " + contained.opposite());

            String oppositeOfGetContainedProperty = contained.opposite();

            // System.out.println("Container: " + container.opposite());
            Optional<Prop> opposite = parent.getModel().resolveOppositeOf(getParent(), oppositeOfGetContainedProperty);

            // if opposite can't be found, try with full name
            if (!opposite.isPresent()) {
                if (isCollectionType()) {
                    oppositeOfGetContainedProperty = getGenericTypeName() + "." + oppositeOfGetContainedProperty;
                } else {
                    oppositeOfGetContainedProperty = getTypeName() + "." + oppositeOfGetContainedProperty;
                }
                opposite = parent.getModel().resolveOppositeOf(getParent(), oppositeOfGetContainedProperty);
            }


            if (opposite.isPresent()) {
                this.containmentInfo = ContainmentInfo.newInstance(
                        parent, this, opposite.get().getParent(), opposite.get(), ContainmentType.CONTAINED);
            } else {
                throw new RuntimeException(
                        "Specified opposite property '" + oppositeOfGetContainedProperty + "' cannot be found");
            }
        } else {
            this.containmentInfo = ContainmentInfo.newInstance(null, null,null, null, ContainmentType.NONE);
        }
    }

    void initSyncInfo() {

        // containment
        SyncWith syncInfo = getterMethod.getAnnotation(SyncWith.class);

        if (syncInfo != null) {

            System.out.println("init sync info for " + getName());

            String oppositeOfSyncProperty = syncInfo.opposite();

            // System.out.println("Container: " + container.opposite());
            Optional<Prop> opposite = parent.getModel().resolveOppositeOf(getParent(), oppositeOfSyncProperty);

            // if opposite can't be found, try with full name
            if (!opposite.isPresent()) {
                if (isCollectionType()) {
                    oppositeOfSyncProperty = getGenericTypeName() + "." + oppositeOfSyncProperty;
                } else {
                    oppositeOfSyncProperty = getTypeName() + "." + oppositeOfSyncProperty;
                }

                opposite = parent.getModel().resolveOppositeOf(getParent(), oppositeOfSyncProperty);
            }


            if (opposite.isPresent()) {
                this.syncInfo = SyncInfo.newInstance(
                        parent, opposite.get().getParent(), opposite.get());
            } else {
                throw new RuntimeException(
                        "Specified opposite property '" + oppositeOfSyncProperty + "' cannot be found");
            }
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

    public SyncInfo getSyncInfo() {
        return syncInfo;
    }

    public boolean isRequired() {
        return required;
    }

    public String getGetterPrefix() {
        return getterPrefix;
    }

    public String getGetterDeclaration() {
        return getTypeName() + " " + getGetterPrefix() + getNameWithUpperCase() + "()";
    }

    public String getSetterDeclaration() {
        return "void set" + getNameWithUpperCase() + "(" + getTypeName() + " " + getName() + ")";
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

    public String getGenericTypeName() {
        if (isCollectionType()) {
            return genericTypeName;
        }

        return null;
    }

    public String getGenericPackageName() {
        if (isCollectionType()) {
            return genericPackageName;
        }

        return null;
    }

    public ModelType getGenericType() {
        return getParent().getModel().resolveType(
                getGenericPackageName() + "." + getGenericTypeName()).
                orElse(null);
    }

    public ModelType getType() {
        if (this.type == null) {
            this.type = getParent().getModel().
                    resolveType(getPackageName() + "." + getTypeName()).
                    orElse(null);
        }

        return this.type;
    }

    public int getTypeId() {
        if (getType() != null) {
            return getType().getTypeId();
        } else if (isCollectionType()) {
            return -2;
        } else {
            return -1;
        }
    }

    public boolean isGetterOnly() {
        return getterOnly;
    }

    static String propertyNameFromGetter(Method getterMethod) {

        String usedGetterPrefix;

        if (getterMethod.getName().startsWith("is")) {

            if (!"is".equals(computeGetterPrefix(getterMethod))) {
                throw new RuntimeException("Method '"
                        + getterMethod.getDeclaringClass().getName()
                        + "#" + getterMethod.getName()
                        + "' uses wrong prefix.");
            }

            usedGetterPrefix = "is";
        } else {
            usedGetterPrefix = "get";
        }

        String name = getterMethod.getName().substring(usedGetterPrefix.length());
        name = name.substring(0, 1).toLowerCase() + name.substring(1);
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Prop prop = (Prop) o;

        if (!name.equals(prop.name)) {
            return false;
        }
        if (!packageName.equals(prop.packageName)) {
            return false;
        }
        return typeName.equals(prop.typeName);
    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + packageName.hashCode();
        result = 31 * result + typeName.hashCode();
        return result;
    }

    static String computeGetterPrefix(Method getterMethod) {
        String getterPrefix;
        // check whether boolean is used, getter uses isXXX convention in this case
        if (Objects.equals(getterMethod.getReturnType(), boolean.class)) {
            getterPrefix = "is";
        } else {
            getterPrefix = "get";
        }

        return getterPrefix;
    }

    static List<Prop> filterDuplicateProps(List<Prop> properties) {
        List<Prop> result = new ArrayList(properties);
        List<String> distinctNames = result.stream().
                map(p->p.getName()).distinct().collect(Collectors.toList());

        List<Prop> distinctProperties = new ArrayList<>();

        for(String pName : distinctNames) {
            List<Prop> collidingProps = result.stream().
                    filter(p-> Objects.equals(pName, p.getName())).collect(Collectors.toList());

            if(collidingProps.size() < 2) {
                continue;
            }

            Prop p = collidingProps.get(0);

            for(Prop otherP : collidingProps) {

                if(Objects.equals(p, otherP)) {
                    continue;
                }

                if(Objects.equals(p.getTypeName(), otherP.getTypeName())) {
                    continue;
                } else {

                    throw new RuntimeException("" +
                            "Inherited properties are not allowed to change type.\n\n" +
                            "Property:\n" +
                            " -> " + p.getParent().getFullTypeName()+"."+p.getName() + "\n"+
                            "Involved types:\n" +
                            " -> " +  p.getTypeName() + "\n" +
                            " -> " + otherP.getTypeName());
                }
            }

            distinctProperties.add(p);
        }

        for(Prop dP : distinctProperties) {
            for (Iterator<Prop> it = result.iterator(); it.hasNext(); ) {
                Prop p = it.next();

                if(Objects.equals(dP.getName(), p.getName())) {
                    it.remove();
                }
            }
        }

        result.addAll(distinctProperties);

        return result;
    }

// CURRENTLY this method is unused, since we do use property information from parent types which check annotations
//           we leave this code here (just in case...).
//    /**
//     * Returns the method's (or it's equivalents in super types) annotation for the specified type.
//     *
//     * @param m method
//     * @param a annotation type
//     * @param <A> annotation class
//     * @return annotation for the specified type if such an annotation is present; {@code null} otherwise
//     */
//    private static <A extends Annotation> A getAnnotationOfMethodOrSuperTypeMethod(Method m, Class<A> a) {
//
//        A annotation = m.getAnnotation(a);
//
//        System.out.println("M: " + m.getName() + ", " + m.getDeclaringClass().getName() + ", " + annotation);
//
//        if (annotation != null) {
//            return annotation;
//        }
//
//        for (Class<?> ifc : m.getDeclaringClass().getInterfaces()) {
//
//            try {
//                Method newM = ifc.getMethod(m.getName(), m.getParameterTypes());
//                annotation = getAnnotationOfMethodOrSuperTypeMethod(newM, a);
//                if (annotation!=null) {
//                   return annotation;
//                }
//            } catch (NoSuchMethodException e) {
//                // we skip methods that are not found
//            }
//
//        }
//
//        return null;
//    }
}
