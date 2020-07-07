/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
@Deprecated
public class Prop {

    // method used to specify this property in the model interface (subpackage
    // '.vmfmodel')
    private final Method getterMethod;

    // property name
    private final String name;

    // package name, e.g., 'eu.mihosoft.vmf.tutorial'
    private String packageName;

    // type name without package, e.g. 'MyType' instead of 'mypackage.MyType' in
    // case of
    // model types, full typename with package for external types that don't belong
    // to
    // the package
    private String typeName;

    // type name without package, e.g. 'MyType' instead of 'mypackage.MyType'
    private String simpleTypeName;

    // parent type, i.e., property owner
    private final ModelType parent;

    // property customOrderIndex (specified if custom order should be used)
    private Integer customOrderIndex;

    // containment info (container or contained property)
    private ContainmentInfo containmentInfo;
    
    // reference info (cross ref between properties)
    private ReferenceInfo referenceInfo;

    // sync info
    private SyncInfo syncInfo;

    // indicates whether this property is required (validation & constructors)
    // TODO 07.11.2017: actually use required (since we don't have no-default
    // constructors, usage is probably limited to builders & validation)
    private boolean required;

    // indicates whether this property should be ignored for equals() code
    // generation
    private boolean ignoredForEquals;

    // indicates whether this property should be ignored for toString() code
    // generation
    private boolean ignoredForToString;

    // getter prefix (get or is)
    private String getterPrefix;

    // type of the property, e.g., primitive or Collection
    private PropType propType;
    private ModelType type;

    private String genericPackageName;
    private String genericTypeName;
    private CollectionType collectionType;
    private boolean getterOnly;
    private boolean readOnly;

    private String defaultValueAsString;

    private final List<AnnotationInfo> annotations = new ArrayList<>();

    public Collection<AnnotationInfo> getAnnotations() {
        return annotations;
    }

    private int propId;

    private String customDocumentation="";

    // private final static String PUBLIC_LIST_TYPE="java.util.List";
    private final static String PRIVATE_LIST_TYPE_WITHOUT_PKG="VList";
    private final static String PRIVATE_LIST_TYPE_PKG_NAME = "eu.mihosoft.vcollections";
    private final static String PRIVATE_LIST_TYPE_WITH_PKG=PRIVATE_LIST_TYPE_PKG_NAME+"."+PRIVATE_LIST_TYPE_WITHOUT_PKG;

    // currently, we expose VList interface. we might want to use plain list instead.
    // private final static String PUBLIC_LIST_TYPE_WITH_PKG="java.util.List";
    private final static String PUBLIC_LIST_TYPE_WITH_PKG=PRIVATE_LIST_TYPE_WITH_PKG;
    

    private Prop(ModelType parent, Method getterMethod) {
        this.getterMethod = getterMethod;
        name = propertyNameFromGetter(getterMethod);
        this.parent = parent;

        initType(parent.getModel(), getterMethod);
    }

    Prop(Prop other) {
        this.getterMethod = other.getterMethod;
        this.name = other.name;
        this.packageName = other.packageName;
        this.typeName = other.typeName;
        this.simpleTypeName = other.simpleTypeName;
        this.parent = other.parent;
        this.customOrderIndex = other.customOrderIndex;
        this.containmentInfo = other.containmentInfo;
        this.referenceInfo = other.referenceInfo;
        this.syncInfo = other.syncInfo;
        this.required = other.required;
        this.ignoredForEquals = other.ignoredForEquals;
        this.ignoredForToString = other.ignoredForToString;
        this.getterPrefix = other.getterPrefix;
        this.propType = other.propType;
        this.type = other.type;
        this.genericPackageName = other.genericPackageName;
        this.genericTypeName = other.genericTypeName;
        this.collectionType = other.collectionType;
        this.getterOnly = other.getterOnly;
        this.readOnly = other.readOnly;
        this.defaultValueAsString = other.defaultValueAsString;
        this.annotations.addAll(other.getAnnotations());
        this.propId = other.propId;
        this.customDocumentation=other.getCustomDocumentation();
    }

    void initType(Model m, Method getterMethod) {

        Class<?> propClass = getterMethod.getReturnType();

        intitCustomDocumentation(getterMethod);

        PropertyOrder propOrder = getterMethod.getAnnotation(PropertyOrder.class);
        if (propOrder != null) {
            customOrderIndex = propOrder.index();
        }

        if (propClass.isPrimitive()) {
            propType = PropType.PRIMITIVE;
            typeName = propClass.getSimpleName();
            simpleTypeName = propClass.getSimpleName();
            packageName = "";

        } else if (Collection.class.isAssignableFrom(propClass)) {
            propType = PropType.COLLECTION;

            ParameterizedType retType = null;

            if (getterMethod.getGenericReturnType() != null) {
                if (getterMethod.getGenericReturnType() instanceof ParameterizedType) {
                    retType = (ParameterizedType) getterMethod.getGenericReturnType();
                }
            }

            Class<?> containedClazz;

            if (retType != null) {
                containedClazz = (Class<?>) (retType.getActualTypeArguments()[0]);
            } else {
                containedClazz = Object.class;
            }

            if (!List.class.isAssignableFrom(propClass)) {
                throw new IllegalArgumentException(
                        "Currently only 'java.util.List<?>' is supported as Collection type.");
            } else {
                collectionType = CollectionType.LIST;
                if (TypeUtil.getPackageName(containedClazz).isEmpty()) {
                    genericPackageName = "";
                } else {
                    genericPackageName = m.convertModelPackageToDestination(
                            containedClazz.getSimpleName(),
                            TypeUtil.getPackageName(containedClazz));
                }

                genericTypeName = containedClazz.getSimpleName();
            }

            typeName = PRIVATE_LIST_TYPE_WITH_PKG + "<" + m.convertModelTypeToDestination(containedClazz) + ">";
            simpleTypeName = PRIVATE_LIST_TYPE_WITHOUT_PKG + "<" + m.convertModelTypeToDestination(containedClazz) + ">";
            packageName = PRIVATE_LIST_TYPE_PKG_NAME;

        } else if (propClass.isArray()) {
            propType = PropType.COLLECTION;
            Class<?> containedClazz = propClass.getComponentType();
            simpleTypeName = PRIVATE_LIST_TYPE_WITHOUT_PKG+"<" + ModelType.primitiveToBoxedType(m.convertModelTypeToDestination(containedClazz))
                    + ">";
            typeName = PRIVATE_LIST_TYPE_WITH_PKG+"<"
                    + ModelType.primitiveToBoxedType(m.convertModelTypeToDestination(containedClazz)) + ">";
            // System.out.println("TYPENAME: " + typeName);

            packageName = PRIVATE_LIST_TYPE_PKG_NAME;

            collectionType = CollectionType.LIST;
            if (TypeUtil.getPackageName(containedClazz).isEmpty()) {
                genericPackageName = "";
            } else {
                genericPackageName = m.convertModelPackageToDestination(
                        containedClazz.getSimpleName(),
                        TypeUtil.getPackageName(containedClazz));
            }

            // System.out.println("CONTAINED_TYPE: " + containedClazz.getSimpleName());

            genericTypeName = containedClazz.getSimpleName();
        } else {
            propType = PropType.CLASS;

            // if (getParent().getModel().isModelType(propClass.getName())) {
            // typeName = propClass.getSimpleName();
            // } else {
            // typeName = propClass.getName();
            // }

            simpleTypeName = propClass.getSimpleName();

            this.packageName = getParent().getModel()
                    .convertModelPackageToDestination(propClass.getSimpleName(), TypeUtil.getPackageName(propClass));

            typeName = this.packageName + "." + propClass.getSimpleName();
        }

        getterPrefix = computeGetterPrefix(getterMethod);

        required = getterMethod.getAnnotation(Required.class) != null;
        ignoredForEquals = getterMethod.getAnnotation(IgnoreEquals.class) != null;
        ignoredForToString = getterMethod.getAnnotation(IgnoreToString.class) != null;

        getterOnly = getterMethod.getAnnotation(GetterOnly.class) != null;

        DefaultValue defVal = getterMethod.getAnnotation(DefaultValue.class);

        if (defVal != null) {
            defaultValueAsString = defVal.value();
        }

        Annotation[] annotationObjects = getterMethod.getDeclaredAnnotationsByType(Annotation.class);

        for (Annotation a : annotationObjects) {
            annotations.add(new AnnotationInfo(a.key(), a.value()));
        }

        // we require alphabetic order (by key)
        Collections.sort(annotations, Comparator.comparing(AnnotationInfo::getKey));

    }

    private void intitCustomDocumentation(Method m) {
        Doc doc = m.getDeclaredAnnotation(Doc.class);

        if(doc!=null) {
            setCustomDocumentation(doc.value());
        }
    }

    /**
     * @param customDocumentation the customDocumentation to set
     */
    void setCustomDocumentation(String customDocumentation) {
        this.customDocumentation = customDocumentation;
    }

    /**
     * @return the customDocumentation
     */
    public String getCustomDocumentation() {
        return customDocumentation;
    }

    public boolean isDocumented() {
        return getCustomDocumentation()!=null && !getCustomDocumentation().isEmpty();
    }

    void setPropId(int propId) {
        this.propId = propId;
    }

   public int getPropId() {
       return this.propId;
   }

//   /**
//    * Returns this property and reinterprets it as property of the specified type (only by name).
//    * Only use this methods on types that this property's parent inherits from.
//    * @param t type to reinterpret this property to
//    * @return this property and reinterprets it as property of the specified type (only by name)
//    * @throws RuntimeException if the property cannot be found in the specified type
//    */
//    public Prop mapToPropertyOfType(ModelType t) {
//        return t.getImplementation().getProperties().stream().
//            filter(p->getName().equals(p.getName())).findFirst().
//                orElseThrow(()->new RuntimeException(
//                    "Cannot find property '"+getName()+"' in type '"+t.getFullTypeName()+"'")
//            );
//    }
//
//   /**
//    * Returns this property and reinterprets it as property of the specified type (only reinterprets by name).
//    * Only use this methods on types that this property's parent inherits from.
//    * @param typeName name of the type to reinterpret this property to
//    * @return this property and reinterprets it as property of the specified type (only reinterprets by name).
//    * @throws RuntimeException if the property cannot be found in the specified type
//    */
//    public Prop mapToPropertyOfTypeByTypeName(String typeName) {
//
//        ModelType t = getParent().getModel().getTypes().stream().
//            filter(type->Objects.equals(typeName, type.getTypeName())
//                || Objects.equals(typeName, type.getFullTypeName())).findAny().
//              orElseThrow(()->new RuntimeException(
//                    "Cannot find type '"+typeName+"' in this model"));
//
//        return t.getImplementation().getProperties().stream().
//            filter(p->getName().equals(p.getName())).findFirst().
//                orElseThrow(()->new RuntimeException(
//                    "Cannot find property '"+getName()+"' in type '"+t.getFullTypeName()+"'")
//            );
//    }

    void initContainment() {

        // System.out.println("init containment for " + getName());

        // containment
        Container container = getterMethod.getAnnotation(Container.class);
        Contains contained = getterMethod.getAnnotation(Contains.class);

        if (container != null) {

            String oppositeOfGetContainerProperty = container.opposite();

            if(oppositeOfGetContainerProperty.isEmpty()) {
                this.readOnly = true;
                this.containmentInfo = ContainmentInfo.newInstance(null, null, null, null, ContainmentType.CONTAINER);
            } else {    
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
                    this.containmentInfo = ContainmentInfo.newInstance(parent, this, opposite.get().getParent(),
                            opposite.get(), ContainmentType.CONTAINER);
                } else {
                    throw new RuntimeException(
                            "Specified opposite property '" + oppositeOfGetContainerProperty + "' cannot be found");
                }
            }
        } else if (contained != null) {
            // System.out.println("Contained: " + contained.opposite());

            String oppositeOfGetContainedProperty = contained.opposite();

            if(Contains.NO_OPPOSITE_PROPERTY.equals(oppositeOfGetContainedProperty)) {
                // no opposite specified. this is allowed for the `@contains` annotation
                // but not allowed for `@container` annotation
                this.containmentInfo = ContainmentInfo.newInstance(parent, this, null,
                            null, ContainmentType.CONTAINED);
            } else {
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
                    this.containmentInfo = ContainmentInfo.newInstance(parent, this, opposite.get().getParent(),
                            opposite.get(), ContainmentType.CONTAINED);
                } else {
                    throw new RuntimeException(
                            "Specified opposite property '" + oppositeOfGetContainedProperty + "' cannot be found");
                }
            }
        } else {
            this.containmentInfo = ContainmentInfo.newInstance(null, null, null, null, ContainmentType.NONE);
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
                this.syncInfo = SyncInfo.newInstance(parent, opposite.get().getParent(), opposite.get());
            } else {
                throw new RuntimeException(
                        "Specified opposite property '" + oppositeOfSyncProperty + "' cannot be found");
            }
        }
    }

    void initCrossRefInfo() {

        // containment
        Refers referenceInfo = getterMethod.getAnnotation(Refers.class);

        if (referenceInfo != null) {

            // System.out.println("init cross-ref info for " + getName());

            String oppositeOfSyncProperty = referenceInfo.opposite();

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
                this.referenceInfo = ReferenceInfo.newInstance(
                        parent, 
                        this, 
                        opposite.get().getParent(),
                        opposite.get()
                );
            } else {
                throw new RuntimeException(
                        "Specified opposite property '" + oppositeOfSyncProperty + "' cannot be found");
            }
        }
    }

    public static Prop newInstance(ModelType parent, Method getterMethod) {
        return new Prop(parent, getterMethod);
    }

    // /**
    //  * Creates and returns a shallow clone of this property.
    //  * @return returns a shallow clone of this property
    //  */
    // private Prop shallowClone() {
    //     Prop p = new Prop(parent, getterMethod, name);

    //     // assign all non-final fields declared inside property
    //     for (Field field : Prop.class.getDeclaredFields()) {
    //         if (!Modifier.isFinal(field.getModifiers())) {
    //             try {
    //                 field.set(p, field.get(this));
    //             } catch (IllegalArgumentException | IllegalAccessException e) {
    //                 throw new RuntimeException(
    //                     "Cannot create shallow clone of property '" + name + "'");
    //             }
    //         }
    //     }

    //     return p;
    // }

    // /**
    //  * Creates a shallow copy of this property for inheritance of a mutable type, 
    //  * i.e., {@link GetterOnly} is removed if present. 
    //  * @return a deep copy of this property for inheritance of a mutable type
    //  */
    // public Prop inheritToMutable() {
    //     Prop shallowClone = newInstance(parent, getterMethod);

    //     // remove getter-only annotation
    //     shallowClone.getterOnly = false;

    //     return shallowClone;
    // }

    // /**
    //  * Returns a shallow copy (or just a reference if no changes are necessary) of this property for 
    //  * inheritance of a mutable type, i.e., {@link GetterOnly} is removed if present. 
    //  * @return a deep copy of this property for inheritance of a mutable type
    //  */
    // public Prop inheritToImmutable() {
    //     return this;
    // }

    public boolean isModelType() {
        return getType()!=null;//getParent().getModel().isModelType(getPackageName() + "." + getTypeName());
    }

    public String getTypeNameForInterface() {
        return getTypeName().replace(PRIVATE_LIST_TYPE_WITH_PKG, PUBLIC_LIST_TYPE_WITH_PKG);
    }

    public String getTypeName() {
        return typeName;
    }

    public String getSimpleTypeName() {
        return simpleTypeName;
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

    /**
     * @return the referenceInfo
     */
    public ReferenceInfo getReferenceInfo() {
        return referenceInfo;
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

    public boolean isCrossRefProperty() {
        return getReferenceInfo() != null;
    }

    public String getNameWithUpperCase() {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    /**
     * TODO should not change state, since it is a getter only!
     * @return 
     */
    public boolean isIgnoredForEquals() {

        if(!ignoredForEquals) {

            if(!isModelType()) {
                return false;
            }

            // check whether we should ignore by 
            // equals setting, see VMFEquals.java and VMFModel.java
            if(getParent().isEqualsAndHashCodeCONTAINMENT_AND_EXTERNAL()
                || !getParent().isEqualsAndHashCode()) {

                // we check for containment if we set containment_and_external
                // or if we didn't set a vmf specific implementation
                // in this case containment_and_external is used for the _vmf_equals()
                // method accessible via vobj.vmf().content().equals(other)
                //
                // that is, if we choose the public equals() methods to be
                // instance based we set the vmf specific implementation to
                // containment_and_external
                //
                // TODO 16.10.2019 do we actually need the ALL implementation?

                // if we are not contained, ignore this property
                if(!isContained()) {
                    ignoredForEquals = true;
                }
            }
        }

        return ignoredForEquals;
    }

    public boolean isIgnoredForToString() {
        return ignoredForToString;
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
                    resolveType(getTypeName()).
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

    public String getDefaultValueAsString() {
        return this.defaultValueAsString == null || this.defaultValueAsString.isEmpty() ? "null" : this.defaultValueAsString;
    }

    public void setDefaultValueAsString(String defaultValueAsString) {
        this.defaultValueAsString = defaultValueAsString;
    }

    public boolean isGetterOnly() {
        return getterOnly;
    }
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * Determines whether this property is inherited from a super type.
     * @return {@code true} if it is inherited; {@code false} otherwise
     */
    public boolean isInheritedProp() {
        return getInheritedProp() != null;
    }

    /**
     * Returns the property this property is inherited from.
     * 
     * @InterfaceOnly
     * interface Base {
     *   @GetterOnly // this is important, cannot inherit otherwise
     *   Object getValue();
     * }
     * 
     * interface Inherited {
     *   Integer getValue()
     * }
     * 
     *  -> value property is inherited and changes from Object to Integer
     * 
     * @return returns the property this property is inherited from or
     * {@code null} if this is not the case
     */
    public Prop getInheritedProp() {
        return inheritedProp;
    }
    private Prop inheritedProp;
    private void setInheritedProp(Prop inheritedProp) {
        this.inheritedProp = inheritedProp;
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

    static List<Prop> filterDuplicateProps(List<Prop> properties, boolean throwTypeNotResolvable) {
        List<Prop> result = new ArrayList(properties);
        List<String> distinctNames = result.stream().
                map(p -> p.getName()).distinct().collect(Collectors.toList());

        List<Prop> distinctProperties = new ArrayList<>();

        for (String pName : distinctNames) {
            List<Prop> collidingProps = result.stream().
                    filter(p -> Objects.equals(pName, p.getName())).collect(Collectors.toList());

            if (collidingProps.size() < 2) {
                continue;
            }

            Prop p = collidingProps.get(0);

            for (Prop otherP : collidingProps) {

                if (Objects.equals(p, otherP)) {
                    continue;
                }

                
                boolean firstIsModelType = p.getType() != null;
                boolean secondIsModelType = otherP.getType() != null;
                boolean modelType = firstIsModelType && secondIsModelType;

                if (modelType && otherP.getType().extendsType(p.getType())) {
                    // System.out.println("Extends: " + p.getTypeName() + " -> " + otherP.getTypeName());
                    p = otherP; // otherP is more specific than p

                    // set inherited param
                    otherP.setInheritedProp(p);
                } else if (modelType && p.getType().extendsType(otherP.getType())) {
                    // System.out.println("Extends: " + otherP.getTypeName() + " -> " + p.getTypeName());
                    // nothing to do since p is the most specific one already (p = p);

                    // set inherited param
                    p.setInheritedProp(otherP);
                } else if (!modelType && Objects.equals(p.getTypeName(), otherP.getTypeName())) {
                    // types are identical. nothing to do
                } else if (!modelType) {

                    if(firstIsModelType && !secondIsModelType) {
                        ModelType pType = p.getType();

                        if(pType.extendsType(otherP.getTypeName())) {
                            // System.out.println("Extends: " + otherP.getTypeName() + " -> " + p.getTypeName());
                            // nothing to do since p is the most specific one already (p = p);

                            // set inherited param
                            p.setInheritedProp(otherP);
                        }

                    } else if(!firstIsModelType && secondIsModelType) {
                        ModelType otherPType = otherP.getType();

                        if(otherPType.extendsType(p.getTypeName())) {
                            // System.out.println("Extends: " + otherP.getTypeName() + " -> " + p.getTypeName());
                            p = otherP; // otherP is more specific than p

                            // set inherited param
                            otherP.setInheritedProp(p);
                        }

                    } else {

                        // we try to get type information from external types:
                        Optional<Class<?>> pType = p.getParent().getModel().resolveExternalType(p.getTypeName());
                        Optional<Class<?>> otherPType = otherP.getParent().getModel().resolveExternalType(otherP.getTypeName());

                        if (!pType.isPresent() || !otherPType.isPresent()) {
                            if (!pType.isPresent()) {
                                String msg = "Cannot resolve external type '" + p.getTypeName() + "'";
                                System.err.println("TODO [28.05.2018]: " + msg);
                                System.err.println("TODO [28.05.2018]: could be that the types are not yet initialized.");
                                System.err.println("TODO [28.05.2018]: -> see PASS 0 & 4 in Model.java");
                                if (throwTypeNotResolvable) {
                                    throw new RuntimeException(msg);
                                }
                            } else {
                                String msg = "Cannot resolve external type '" + otherP.getTypeName() + "'";
                                System.err.println("TODO [28.05.2018]: " + msg);
                                System.err.println("TODO [28.05.2018]: could be that the types are not yet initialized.");
                                System.err.println("TODO [28.05.2018]: -> see PASS 0 & 4 in Model.java");
                                if (throwTypeNotResolvable) {
                                    throw new RuntimeException(msg);
                                }
                            }
                        } else if (pType.get().isAssignableFrom(otherPType.get())) {
                            // System.out.println("Extends: " + p.getTypeName() + " -> " + otherP.getTypeName());
                            p = otherP; // otherP is more specific than p
                            otherP.setInheritedProp(p);
                        } else if (otherPType.get().isAssignableFrom(pType.get())) {
                            // System.out.println("Extends: " + otherP.getTypeName() + " -> " + p.getTypeName());
                            // nothing to do since p is the most specific one already
                            p.setInheritedProp(otherP);
                        }
                    }
                }
            }

            distinctProperties.add(p);
        }

        for (Prop dP : distinctProperties) {
            for (Iterator<Prop> it = result.iterator(); it.hasNext(); ) {
                Prop p = it.next();

                if (Objects.equals(dP.getName(), p.getName())) {
                    it.remove();
                }
            }
        }

        result.addAll(distinctProperties);

        return result;
    }

    static List<Prop> filterDuplicatePropsExp(List<Prop> properties) {
        List<Prop> result = new ArrayList(properties);
        List<String> distinctNames = result.stream().
                map(p -> p.getName()).distinct().collect(Collectors.toList());

        List<Prop> distinctProperties = new ArrayList<>();

        for (String pName : distinctNames) {
            List<Prop> collidingProps = result.stream().
                    filter(p -> Objects.equals(pName, p.getName())).collect(Collectors.toList());

            if (collidingProps.size() < 2) {
                continue;
            }

            Prop p = collidingProps.get(0);

            for (Prop otherP : collidingProps) {

                if (Objects.equals(p, otherP)) {
                    continue;
                }

                if (Objects.equals(p.getTypeName(), otherP.getTypeName())) {
                    continue;
                } else if (!p.isGetterOnly() && !otherP.isGetterOnly()) {

                    System.err.println("getterp1: " + p.isGetterOnly());
                    System.err.println("getterp2: " + otherP.isGetterOnly());


                }
            }

            distinctProperties.add(p);
        }

        for (Prop dP : distinctProperties) {
            for (Iterator<Prop> it = result.iterator(); it.hasNext(); ) {
                Prop p = it.next();

                if (Objects.equals(dP.getName(), p.getName())) {
                    it.remove();
                }
            }
        }

        result.addAll(distinctProperties);

        return result;
    }

    /**
     * Returns the requested customOrderIndex of this property.
     * @return the requested customOrderIndex of this property
     */
    public Integer getCustomOrderIndex() {
        return customOrderIndex;
    }

    /**
     * Sets the requested customOrderIndex of this property.
     * @param customOrderIndex customOrderIndex to set
     */
    public void setCustomOrderIndex(Integer customOrderIndex) {
        this.customOrderIndex = customOrderIndex;
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
