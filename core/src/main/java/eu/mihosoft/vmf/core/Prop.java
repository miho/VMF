/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
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
    private boolean keyField;
    private String keyPropertyName;
    private Contains keyedContainment;
    private String mapKeyTypeName;
    private String mapValueTypeName;
    private String nestedBinaryName;
    private int lowerBound;
    private int upperBound;
    private boolean ordered;
    private boolean unique;
    private boolean referenceSet;
    private Contains uniqueContainment;
    private Refers uniqueReference;
    private String constrainedElementType;

    private static final String VMAP_NAME = "eu.mihosoft.vcollections.VMap";
    private static final Set<Class<?>> MAP_SCALAR_TYPES = Set.of(String.class, Boolean.class, Byte.class,
            Short.class, Integer.class, Long.class, Float.class, Double.class, Character.class);
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
        this.genericParameter = other.genericParameter;
        this.type = other.type;
        this.genericPackageName = other.genericPackageName;
        this.genericTypeName = other.genericTypeName;
        this.collectionType = other.collectionType;
        this.mapKeyTypeName = other.mapKeyTypeName;
        this.lowerBound = other.lowerBound;
        this.upperBound = other.upperBound;
        this.ordered = other.ordered;
        this.unique = other.unique;
        this.referenceSet = other.referenceSet;
        this.uniqueContainment = other.uniqueContainment;
        this.uniqueReference = other.uniqueReference;
        this.constrainedElementType = other.constrainedElementType;
        this.mapValueTypeName = other.mapValueTypeName;
        this.nestedBinaryName = other.nestedBinaryName;
        this.getterOnly = other.getterOnly;
        this.readOnly = other.readOnly;
        this.defaultValueAsString = other.defaultValueAsString;
        this.annotations.addAll(other.getAnnotations());
        this.propId = other.propId;
        this.customDocumentation=other.getCustomDocumentation();
    }

    Prop inheritedBy(ModelType owner) {
        Prop copy = new Prop(this);
        copy.initKeyContract(owner);
        if (copy.isKeyed() && !isKeyed()) copy.initContainment();
        copy.initMultiplicity(getterMethod, owner);
        if (copy.isMapType()) copy.setMapTypes(owner);
        else if (copy.genericParameter != null) copy.setParameterType(owner);
        return copy;
    }

    private TypeVariable<?> genericParameter;

    private void setParameterType(ModelType owner) {
        GenericModelTypes types = owner.genericTypes();
        Type resolved = types.resolve(genericParameter);
        String name = types.name(resolved);
        captureNestedBinaryName(resolved);
        type = null;
        if (isManagedValues()) {
            setConstrainedElement(resolved, owner);
        } else if (isCollectionType()) {
            initListType(name);
        } else {
            int separator = name.lastIndexOf('.');
            packageName = separator < 0 ? "" : name.substring(0, separator);
            simpleTypeName = name.substring(separator + 1);
            typeName = name;
        }
    }

    private void initListType(String elementName) {
        int separator = elementName.lastIndexOf('.');
        genericPackageName = separator < 0 ? "" : elementName.substring(0, separator);
        genericTypeName = elementName.substring(separator + 1);
        propType = PropType.COLLECTION;
        collectionType = CollectionType.LIST;
        packageName = PRIVATE_LIST_TYPE_PKG_NAME;
        simpleTypeName = PRIVATE_LIST_TYPE_WITHOUT_PKG + "<" + elementName + ">";
        typeName = PRIVATE_LIST_TYPE_WITH_PKG + "<" + elementName + ">";
    }

    private void rejectGenericModelProperty(Method method, Class<?> type) {
        if (type.getTypeParameters().length != 0 && type.getPackageName().equals(method.getDeclaringClass().getPackageName())
                && !type.isAnnotationPresent(ExternalType.class)) {
            throw GenericModelTypes.unsupportedProperty(method, "use a named specialization of " + type.getTypeName());
        }
    }

    public int getLowerBound() { return lowerBound; }
    public int getUpperBound() { return upperBound; }
    public boolean isOrdered() { return ordered; }
    public boolean isUnique() { return unique; }
    public boolean isSetType() { return propType == PropType.SET; }
    public boolean isConstrainedList() { return propType == PropType.CONSTRAINED_LIST; }
    public boolean isManagedValues() { return isSetType() || isConstrainedList(); }
    public String getManagedCollectionName() { return isSetType() ? "eu.mihosoft.vcollections.VSet" : PRIVATE_LIST_TYPE_WITH_PKG; }
    public String getConstrainedElementType() { return constrainedElementType; }

    private void setConstrainedElement(Type element, ModelType owner) {
        if (!(element instanceof TypeVariable<?>) && !MAP_SCALAR_TYPES.contains(element)) {
            throw GenericModelTypes.unsupportedProperty(getterMethod, "constrained collection elements must be String or boxed primitives; found "
                    + element.getTypeName() + " in " + owner.getFullTypeName());
        }
        constrainedElementType = owner.genericTypes().name(element);
        String raw = isSetType() ? "VSet" : "VList";
        packageName = PRIVATE_LIST_TYPE_PKG_NAME;
        simpleTypeName = raw + "<" + constrainedElementType + ">";
        typeName = packageName + "." + simpleTypeName;
    }

    private static Multiplicity.Kind declaredKind(Multiplicity declaration, boolean set, boolean list) {
        if (declaration.kind() != Multiplicity.Kind.INFERRED) return declaration.kind();
        return set ? Multiplicity.Kind.SET : list ? Multiplicity.Kind.SEQUENCE : Multiplicity.Kind.INFERRED;
    }

    private static int declaredUpper(Multiplicity declaration, boolean many) {
        return !many && declaration.upper() == -1 ? 1 : declaration.upper();
    }

    private void initMultiplicity(Method getter, ModelType owner) {
        Class<?> raw = getter.getReturnType();
        boolean set = raw == Set.class || raw.getName().equals("eu.mihosoft.vcollections.VSet");
        boolean list = raw.isArray() || List.class.isAssignableFrom(raw);
        boolean map = raw.getName().equals(VMAP_NAME);
        Multiplicity m = null;
        Method origin = null;
        required = false;
        boolean many = list || set || map;
        boolean relation = false;
        for (Method declaration : owner.genericTypes().propertyDeclarations(getter.getName())) {
            required |= declaration.isAnnotationPresent(Required.class);
            relation |= declaration.isAnnotationPresent(Contains.class) || declaration.isAnnotationPresent(Container.class)
                    || declaration.isAnnotationPresent(Refers.class) || declaration.isAnnotationPresent(SyncWith.class)
                    || declaration.isAnnotationPresent(Keyed.class);
            Multiplicity candidate = declaration.getAnnotation(Multiplicity.class);
            if (candidate == null) continue;
            if (m != null && (m.lower() != candidate.lower() || declaredUpper(m, many) != declaredUpper(candidate, many)
                    || declaredKind(m, set, list) != declaredKind(candidate, set, list))) {
                throw new IllegalArgumentException("Incompatible multiplicity for " + owner.getFullTypeName() + "."
                        + getter.getName() + " inherited from " + origin.toGenericString() + " and " + declaration.toGenericString());
            }
            m = candidate;
            origin = declaration;
        }
        lowerBound = m == null ? (raw.isPrimitive() || required ? 1 : 0) : m.lower();
        upperBound = m == null ? (set || list || map ? -1 : 1) : m.upper();
        Multiplicity.Kind kind = m == null ? Multiplicity.Kind.INFERRED : m.kind();
        if (kind == Multiplicity.Kind.BAG)
            throw GenericModelTypes.unsupportedProperty(getter, "unordered nonunique collections (bags) are unsupported");
        if (set && kind != Multiplicity.Kind.INFERRED && kind != Multiplicity.Kind.SET)
            throw GenericModelTypes.unsupportedProperty(getter, "Set requires SET; declare an ordered List for positions");
        if (list && kind == Multiplicity.Kind.SET)
            throw GenericModelTypes.unsupportedProperty(getter, "declare Set for unordered unique values");
        if (!set && !list && kind != Multiplicity.Kind.INFERRED)
            throw GenericModelTypes.unsupportedProperty(getter, "collection kinds require List, array, or Set declarations");
        ordered = list;
        unique = !list || kind == Multiplicity.Kind.ORDERED_SET;
        if (required && lowerBound == 0) throw GenericModelTypes.unsupportedProperty(getter,
                "@Required requires a positive lower bound in " + owner.getFullTypeName());
        if (lowerBound < 0)
            throw GenericModelTypes.unsupportedProperty(getter, "negative lower bound");
        if (!set && !list && !map) {
            if (upperBound == -1) upperBound = 1;
            if (upperBound != 0 && upperBound != 1)
                throw GenericModelTypes.unsupportedProperty(getter, "a scalar has upper bound 0 or 1");
            if (raw.isPrimitive() && (lowerBound != 1 || upperBound != 1))
                throw GenericModelTypes.unsupportedProperty(getter, "a primitive always has multiplicity 1..1; use a boxed type for optional values");
        }
        if (upperBound < -1 || (upperBound != -1 && lowerBound > upperBound))
            throw GenericModelTypes.unsupportedProperty(getter, "invalid multiplicity " + lowerBound + ".." + upperBound);
        if (map) return;
        if (!set && (!list || (!unique && upperBound == -1))) return;

        Class<?> target = getValueClass();
        if (target != null && owner.getModel().declares(target) && unique) {
            referenceSet = true;
            for (Method declaration : owner.genericTypes().propertyDeclarations(getter.getName())) {
                Contains contains = declaration.getAnnotation(Contains.class);
                Refers refers = declaration.getAnnotation(Refers.class);
                if (contains != null) {
                    if (uniqueContainment != null && !uniqueContainment.equals(contains))
                        throw GenericModelTypes.unsupportedProperty(getter, "conflicting containment opposites on a unique reference");
                    uniqueContainment = contains;
                }
                if (refers != null) {
                    if (uniqueReference != null && !uniqueReference.equals(refers))
                        throw GenericModelTypes.unsupportedProperty(getter, "conflicting reference opposites on a unique reference");
                    uniqueReference = refers;
                }
                if (declaration.isAnnotationPresent(Container.class) || declaration.isAnnotationPresent(SyncWith.class))
                    throw GenericModelTypes.unsupportedProperty(getter, "unique reference ends allow @Contains or @Refers");
            }
            if (uniqueContainment != null && uniqueReference != null)
                throw GenericModelTypes.unsupportedProperty(getter, "a unique reference cannot combine @Contains and @Refers");
            if (target.isAnnotationPresent(Immutable.class) && (uniqueContainment != null || uniqueReference != null))
                throw GenericModelTypes.unsupportedProperty(getter, "an immutable target cannot receive a container or opposite reference");
            return;
        }
        if (relation && list && !unique) return;
        if (relation) throw GenericModelTypes.unsupportedProperty(getter, "constrained collections of model relationships are unsupported");
        Type declared = getter.getGenericReturnType();
        Type element;
        if (raw.isArray()) {
            element = declared instanceof GenericArrayType ? ((GenericArrayType) declared).getGenericComponentType() : raw.getComponentType();
            if (element instanceof Class<?> && ((Class<?>) element).isPrimitive()) {
                try { element = Class.forName("java.lang." + ModelType.primitiveToBoxedType(element.getTypeName())); }
                catch (ClassNotFoundException impossible) { throw new AssertionError(impossible); }
            }
        } else {
            if (!(declared instanceof ParameterizedType)) throw GenericModelTypes.unsupportedProperty(getter,
                    "constrained collections require a concrete scalar element or type parameter");
            element = ((ParameterizedType) declared).getActualTypeArguments()[0];
        }
        if (list && !unique && element instanceof Class<?> && ((Class<?>) element).getPackageName().equals(getter.getDeclaringClass().getPackageName())) return;
        if (getter.getTypeParameters().length > 0) throw GenericModelTypes.unsupportedProperty(getter,
                "declare type parameters on the model interface");
        propType = set ? PropType.SET : PropType.CONSTRAINED_LIST;
        if (element instanceof TypeVariable<?>) genericParameter = (TypeVariable<?>) element;
        setConstrainedElement(element, parent);
    }

    private static TypeVariable<?> propertyParameter(Method getter) {
        Type type = getter.getGenericReturnType();
        if (getter.getTypeParameters().length > 0) {
            throw GenericModelTypes.unsupportedProperty(getter, "declare type parameters on the model interface");
        }
        // Managed maps validate both their key and value arguments together.
        if (VMAP_NAME.equals(getter.getReturnType().getName())) return null;
        if (type instanceof GenericArrayType) type = ((GenericArrayType) type).getGenericComponentType();
        if (type instanceof ParameterizedType && List.class.isAssignableFrom(getter.getReturnType())) {
            type = ((ParameterizedType) type).getActualTypeArguments()[0];
        }
        if (type instanceof TypeVariable<?>) {
            if (getter.isAnnotationPresent(Contains.class) || getter.isAnnotationPresent(Container.class)
                    || getter.isAnnotationPresent(Refers.class) || getter.isAnnotationPresent(SyncWith.class)) {
                throw GenericModelTypes.unsupportedProperty(getter, "generic value properties cannot declare model relationships");
            }
            return (TypeVariable<?>) type;
        }
        if (GenericModelTypes.containsParameter(type)) {
            throw GenericModelTypes.unsupportedProperty(getter, "use a scalar type parameter, a list of that parameter, or its array");
        }
        return null;
    }

    void initType(Model m, Method getterMethod) {

        Class<?> propClass = getterMethod.getReturnType();
        if (GenericModelTypes.containsReferenceFacade(getterMethod.getGenericReturnType())) {
            throw GenericModelTypes.unsupportedProperty(getterMethod,
                    "RefSet and OrderedRefSet are generated runtime interfaces. Declare Set<Model> "
                    + "or @Multiplicity(kind = ORDERED_SET) Model[] in the model definition.");
        }

        intitCustomDocumentation(getterMethod);

        PropertyOrder propOrder = getterMethod.getAnnotation(PropertyOrder.class);
        if (propOrder != null) {
            customOrderIndex = propOrder.index();
        }

        initMultiplicity(getterMethod, parent);
        if (!isManagedValues()) genericParameter = propertyParameter(getterMethod);
        if (isManagedValues()) {
            // initMultiplicity resolves the element and generated collection kind together.
        } else if (genericParameter != null) {
            boolean many = propClass.isArray() || List.class.isAssignableFrom(propClass);
            propType = many ? PropType.COLLECTION : PropType.CLASS;
            setParameterType(parent);
        } else if (propClass.isPrimitive()) {
            propType = PropType.PRIMITIVE;
            typeName = propClass.getSimpleName();
            simpleTypeName = propClass.getSimpleName();
            packageName = "";

        } else if (VMAP_NAME.equals(propClass.getName())) {
            initManagedMap(getterMethod);
        } else if (propClass.isArray() || Collection.class.isAssignableFrom(propClass)) {
            Type component;
            if (propClass.isArray()) {
                if (getterMethod.getGenericReturnType() instanceof GenericArrayType) {
                    throw GenericModelTypes.unsupportedProperty(getterMethod,
                            "array elements require a named type or a model type parameter");
                }
                component = propClass.getComponentType();
            } else {
                if (!List.class.isAssignableFrom(propClass) && !referenceSet) {
                    throw GenericModelTypes.unsupportedProperty(getterMethod, "only List is supported as a collection");
                }
                Type declared = getterMethod.getGenericReturnType();
                component = declared instanceof ParameterizedType
                        ? ((ParameterizedType) declared).getActualTypeArguments()[0] : Object.class;
            }
            if (!(component instanceof Class<?>) || ((Class<?>) component).isArray()) {
                throw GenericModelTypes.unsupportedProperty(getterMethod, "list elements require a named type or type parameter");
            }
            Class<?> element = (Class<?>) component;
            if (Collection.class.isAssignableFrom(element) || Map.class.isAssignableFrom(element)) {
                throw GenericModelTypes.unsupportedProperty(getterMethod,
                        "nested collection elements require an explicit model property with its own element type");
            }
            rejectGenericModelProperty(getterMethod, element);
            String elementName = element.isPrimitive() ? "java.lang." + ModelType.primitiveToBoxedType(element.getName())
                    : parent.genericTypes().name(element);
            captureNestedBinaryName(element);
            initListType(elementName);
        } else {
            rejectGenericModelProperty(getterMethod, propClass);
            captureNestedBinaryName(propClass);
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

        if (annotations.stream().anyMatch(a -> a.getKey().equals("vmf:collection:key"))) {
            throw GenericModelTypes.unsupportedProperty(getterMethod, "use @Keyed to declare the entry key");
        }
        initKeyContract(parent);

        // we require alphabetic order (by key)
        Collections.sort(annotations, Comparator.comparing(AnnotationInfo::getKey));

    }

    private void captureNestedBinaryName(Type type) {
        nestedBinaryName = null;
        if (type instanceof Class<?>) {
            Class<?> raw = (Class<?>) type;
            if (raw.getEnclosingClass() != null && !raw.isAnnotationPresent(ExternalType.class)) {
                nestedBinaryName = raw.getName();
            }
        }
    }

    /** JVM class lookup requires the binary name of an external nested class. */
    public String getMetadataTypeName() {
        if (referenceSet) return getTypeNameForInterface();
        if (nestedBinaryName == null) return typeName;
        return isCollectionType() ? PRIVATE_LIST_TYPE_WITH_PKG + "<" + nestedBinaryName + ">" : nestedBinaryName;
    }

    public String getMetadataElementTypeName() {
        return nestedBinaryName != null ? nestedBinaryName : getElementTypeName();
    }

    private void initManagedMap(Method getter) {
        Type signature = getter.getGenericReturnType();
        Type[] args = signature instanceof ParameterizedType
                ? ((ParameterizedType) signature).getActualTypeArguments() : new Type[0];
        if (args.length != 2) {
            throw new IllegalArgumentException("Invalid managed map property " + getter.toGenericString()
                    + ". Expected VMap<K, V> with String or boxed primitive key and value types.");
        }
        if (getter.isAnnotationPresent(Contains.class) || getter.isAnnotationPresent(Container.class)
                || getter.isAnnotationPresent(Refers.class) || getter.isAnnotationPresent(SyncWith.class)) {
            throw new IllegalArgumentException("Managed map property " + getter.toGenericString()
                    + " cannot declare containment, opposite references, or synchronization.");
        }
        propType = PropType.MAP;
        setMapTypes(parent);
    }

    private void setMapTypes(ModelType owner) {
        Type[] args = ((ParameterizedType) getterMethod.getGenericReturnType()).getActualTypeArguments();
        mapKeyTypeName = mapArgument(owner, args[0], "key");
        mapValueTypeName = mapArgument(owner, args[1], "value");
        packageName = PRIVATE_LIST_TYPE_PKG_NAME;
        simpleTypeName = "VMap<" + mapKeyTypeName + ", " + mapValueTypeName + ">";
        typeName = packageName + "." + simpleTypeName;
    }

    private String mapArgument(ModelType owner, Type argument, String role) {
        Type binding = owner.genericTypes().resolve(argument);
        if (!(binding instanceof TypeVariable<?>) && !MAP_SCALAR_TYPES.contains(binding)) {
            throw new IllegalArgumentException("Invalid managed map property " + getterMethod.toGenericString()
                    + " in " + owner.getFullTypeName() + ": unsupported " + role + " type " + binding.getTypeName()
                    + ". Use String or a boxed primitive, or model the relationship as keyed entries.");
        }
        return binding.getTypeName();
    }

    public String getMapKeyTypeName() {
        return mapKeyTypeName;
    }

    private void initKeyContract(ModelType owner) {
        List<Method> declarations = owner.genericTypes().propertyDeclarations(getterMethod.getName());
        for (Method declaration : declarations) {
            Keyed keyed = declaration.getDeclaredAnnotation(Keyed.class);
            if (keyed != null) {
                if (keyPropertyName != null && !keyPropertyName.equals(keyed.by())) {
                    throw GenericModelTypes.unsupportedProperty(getterMethod, "conflicting entry keys '"
                            + keyPropertyName + "' and '" + keyed.by() + "' in " + owner.getFullTypeName());
                }
                keyPropertyName = keyed.by();
            }
        }
        if (keyPropertyName == null) return;
        Type entry = null;
        for (Method declaration : declarations) {
            Type declared = declaration.getGenericReturnType();
            Type component = null;
            if (declared instanceof Class<?> && ((Class<?>) declared).isArray()) {
                component = ((Class<?>) declared).getComponentType();
            } else if (declared instanceof GenericArrayType) {
                component = ((GenericArrayType) declared).getGenericComponentType();
            } else if (declared instanceof ParameterizedType && Collection.class.isAssignableFrom(declaration.getReturnType())) {
                component = ((ParameterizedType) declared).getActualTypeArguments()[0];
            }
            if (component != null) {
                Type resolved = owner.genericTypes().resolve(component);
                if (entry != null && !entry.equals(resolved)) {
                    throw GenericModelTypes.unsupportedProperty(getterMethod, "a keyed containment override must preserve"
                            + " its entry type in " + owner.getFullTypeName() + " (" + entry.getTypeName()
                            + " and " + resolved.getTypeName() + ")");
                }
                entry = resolved;
            }
            Contains contains = declaration.getDeclaredAnnotation(Contains.class);
            if (contains != null) {
                if (keyedContainment != null && !keyedContainment.equals(contains)) {
                    throw GenericModelTypes.unsupportedProperty(getterMethod,
                            "conflicting containment opposites on keyed property in " + owner.getFullTypeName());
                }
                keyedContainment = contains;
            }
            if (declaration.isAnnotationPresent(Container.class) || declaration.isAnnotationPresent(Refers.class)
                    || declaration.isAnnotationPresent(SyncWith.class)) {
                throw GenericModelTypes.unsupportedProperty(getterMethod, "keyed entries require a containment list");
            }
        }
        if (annotations.stream().noneMatch(a -> "vmf:collection:key".equals(a.getKey()))) {
            annotations.add(new AnnotationInfo("vmf:collection:key", keyPropertyName));
            annotations.sort(Comparator.comparing(AnnotationInfo::getKey));
        }
    }

    public boolean isKeyed() {
        return keyPropertyName != null;
    }

    public String getKeyPropertyName() {
        return keyPropertyName;
    }

    public Prop getEntryKey() {
        if (!isCollectionType() || !isContained() || getGenericType() == null) {
            throw GenericModelTypes.unsupportedProperty(getterMethod, "@Keyed requires a containment list of model entries");
        }
        return getGenericType().getImplementation().getProperties().stream()
                .filter(p -> p.getName().equals(getKeyPropertyName())).findFirst()
                .orElseThrow(() -> GenericModelTypes.unsupportedProperty(getterMethod,
                        "unknown key property '" + getKeyPropertyName() + "' on " + getElementTypeName()));
    }

    void validateEntryKey(ModelType owner) {
        if (!isKeyed()) return;
        Prop key = getEntryKey();
        String method = getLookupName();
        boolean delegated = owner.getImplementation().getMethodDelegations().stream().anyMatch(d ->
                d.getMethodName().equals(method) && d.getParamTypes().equals(List.of(key.getTypeName())));
        boolean duplicate = owner.getImplementation().getProperties().stream().anyMatch(p -> p != this && p.isKeyed()
                && p.getLookupName().equals(method) && p.getEntryKey().getTypeName().equals(key.getTypeName()));
        if (delegated || duplicate) {
            throw GenericModelTypes.unsupportedProperty(getterMethod, "lookup method conflicts with " + method);
        }
        // A subclass may inherit or override the key getter. Mark the actual setter
        // emitted for each concrete entry type, including closed generic specializations.
        for (ModelType entryType : owner.getModel().getTypes()) {
            if (entryType.extendsType(getGenericType())) {
                for (Prop actual : entryType.getImplementation().getProperties()) {
                    if (!actual.name.equals(key.name)) continue;
                    boolean scalar = actual.propType == PropType.PRIMITIVE || MAP_SCALAR_TYPES.stream()
                            .anyMatch(c -> c.getName().equals(actual.getTypeName()));
                    if ((!scalar && actual.getType() == null) || actual.isContainer() || actual.isContained()
                            || actual.isCrossRefProperty() || actual.getSyncInfo() != null) {
                        throw GenericModelTypes.unsupportedProperty(getterMethod, "key '" + actual.name
                                + "' on " + entryType.getFullTypeName()
                                + " requires a stable scalar or a single unidirectional model reference");
                    }
                    actual.keyField = true;
                }
            }
        }
    }

    public String getLookupName() {
        return "find" + getNameWithUpperCase() + "By" + getEntryKey().getNameWithUpperCase();
    }

    public boolean isKeyField() {
        return keyField;
    }

    public boolean isMapType() {
        return propType == PropType.MAP;
    }

    public String getMapValueTypeName() {
        return mapValueTypeName;
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
        Contains contained = isKeyed() ? keyedContainment : referenceSet ? uniqueContainment : getterMethod.getAnnotation(Contains.class);

        if (container != null) {

            String oppositeOfGetContainerProperty = container.opposite();

            if(oppositeOfGetContainerProperty.isEmpty()) {
                this.readOnly = true;
                this.containmentInfo = ContainmentInfo.newInstance(null, null, null, null, ContainmentType.CONTAINER);
            } else {
                String oppositeName = oppositeOfGetContainerProperty;
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

                // if opposite still can't be found, it may be declared in a super type of the value type
                if (!opposite.isPresent()) {
                    opposite = resolveInheritedOpposite(oppositeName);
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
                String oppositeName = oppositeOfGetContainedProperty;
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

                // if opposite still can't be found, it may be declared in a super type of the value type
                // (e.g. a '@Container' property declared once in a common base interface)
                if (!opposite.isPresent()) {
                    opposite = resolveInheritedOpposite(oppositeName);
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

    /**
     * Resolves the specified opposite property in the super types of this property's value type
     * (the property type or, for collections, the element type). This allows opposites that are
     * declared in a super type, e.g. a {@code @Container} property declared once in a base interface
     * that all contained types extend.
     *
     * @param oppositeName simple name of the opposite property
     * @return the opposite property if it is declared in a super type of the value type
     */
    private Optional<Prop> resolveInheritedOpposite(String oppositeName) {
        Class<?> valueClass = getValueClass();

        if (valueClass == null) {
            return Optional.empty();
        }

        return resolveInheritedOpposite(valueClass, oppositeName, new HashSet<>());
    }

    private Optional<Prop> resolveInheritedOpposite(Class<?> clazz, String oppositeName, Set<Class<?>> visited) {
        for (Class<?> superInterface : clazz.getInterfaces()) {
            if (!visited.add(superInterface)) {
                continue;
            }

            Optional<ModelType> superType = parent.getModel().resolveType(
                    parent.getModel().convertModelTypeToDestination(superInterface));

            if (superType.isPresent()) {
                Optional<Prop> opposite = superType.get().resolveProp(oppositeName);
                if (opposite.isPresent()) {
                    return opposite;
                }
            }

            Optional<Prop> opposite = resolveInheritedOpposite(superInterface, oppositeName, visited);
            if (opposite.isPresent()) {
                return opposite;
            }
        }

        return Optional.empty();
    }

    /**
     * Returns the class of the values of this property (the property type or, for collections and
     * arrays, the element type).
     *
     * @return value class or {@code null} if it cannot be determined
     */
    private Class<?> getValueClass() {
        Class<?> returnType = getterMethod.getReturnType();

        if (returnType.isArray()) {
            return returnType.getComponentType();
        }

        if (Collection.class.isAssignableFrom(returnType)) {
            java.lang.reflect.Type genericType = getterMethod.getGenericReturnType();
            if (genericType instanceof ParameterizedType) {
                java.lang.reflect.Type[] typeArgs = ((ParameterizedType) genericType).getActualTypeArguments();
                if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
                    return (Class<?>) typeArgs[0];
                }
            }
            return null;
        }

        return returnType;
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
        Refers referenceInfo = referenceSet ? uniqueReference : getterMethod.getAnnotation(Refers.class);

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

    public boolean isReferenceSet() { return referenceSet; }

    public boolean hasReferenceDefault() {
        return referenceSet && !"null".equals(getDefaultValueAsString());
    }

    /** The checked relationship list used by opposite maintenance and model infrastructure. */
    public String internalGetter(String receiver) {
        if (referenceSet) {
            return "((__VMF_TYPE_" + parent.getImplementation().getTypeName() + ") " + receiver + ").__vmf_get_prop_" + name + "()";
        }
        return receiver + "." + getterPrefix + getNameWithUpperCase() + "()";
    }

    public boolean hasUniqueReferenceOpposite() {
        return isCrossRefProperty() && (referenceSet || getReferenceInfo().getOpposite().isReferenceSet());
    }

    public String getReferenceCollectionFactory() { return ordered ? "ordered" : "unordered"; }

    public String getReferenceCollectionClass() {
        return ordered ? GenericModelTypes.ORDERED_REF_SET : GenericModelTypes.REF_SET;
    }

    void validateReferenceSetOverride(ModelType owner) {
        if (!referenceSet) return;
        for (ModelType base : owner.getAllInheritedTypes()) {
            for (Prop inherited : base.getImplementation().getProperties()) {
                if (!name.equals(inherited.name)) continue;
                if (!inherited.referenceSet || ordered != inherited.ordered
                        || !Objects.equals(getElementTypeName(), inherited.getElementTypeName()))
                    throw GenericModelTypes.unsupportedProperty(getterMethod,
                            "a unique reference getter must preserve its inherited collection kind and model element type");
            }
        }
    }

    public String getReadOnlyReferenceCollectionType() {
        return getReferenceCollectionClass() + "<" + getGenericType().getReadOnlyInterface().getFullTypeName() + ">";
    }

    public String getTypeNameForInterface() {
        if (referenceSet) return getReferenceCollectionClass() + "<" + getElementTypeName() + ">";
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
        return getTypeNameForInterface() + " " + getGetterPrefix() + getNameWithUpperCase() + "()";
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

    public String getElementTypeName() {
        if (!isCollectionType()) return null;
        return genericPackageName.isEmpty() ? genericTypeName : genericPackageName + "." + genericTypeName;
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
        } else if (isSetType()) {
            return -4;
        } else if (isConstrainedList()) {
            return -2;
        } else if (isMapType()) {
            return -3;
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
