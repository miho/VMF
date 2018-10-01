/*
 * Copyright 2017-2018 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2018 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class ModelType {

    private final String packageName;
    private final String typeName;

    private final List<Prop> properties = new ArrayList<>();
    private final List<Prop> propertiesWithoutCollectionsBasedContainment = new ArrayList<>();
    private boolean customPropertyOrderPresent;

    private final List<String> imports = new ArrayList<>();

    private final Model model;
    private Interface iface;
    private ReadOnlyInterface readOnlyInterface;
    private ReadOnlyImplementation readOnlyImplementation;
    private Implementation implementation;

    private final String ext3ndsString;
    private final String implementzString;
    private final List<String> implementsList = new ArrayList<>();
    private final String writableImplementzString;
    private final String readOnlyImplementzString;
    private final String immutableImplementzString;

    private final List<ModelType> implementz = new ArrayList<>();

    private final boolean immutable;
    private final boolean interfaceOnly;

    private final int typeId;

    private final List<DelegationInfo> delegations = new ArrayList<>();
    private final List<DelegationInfo> methodDelegations = new ArrayList<>();
    private final List<DelegationInfo> constructorDelegations = new ArrayList<>();

    private ModelType(Model model, Class<?> clazz, int typeId) {
        this.model = model;

        this.packageName = model.getPackageName();

        this.typeName = clazz.getSimpleName();
        this.typeId = typeId;

        this.immutable = clazz.getAnnotation(Immutable.class) != null;
        this.interfaceOnly = clazz.getAnnotation(InterfaceOnly.class) != null;

        initDelegations(clazz);

        initProperties(clazz);

        // initImports(imports);

        this.ext3ndsString = generateExtendsString(getModel(), clazz);
        this.implementzString = generateImplementsString(getModel(), clazz);
        this.writableImplementzString = generateWritableImplementsString(getModel(), clazz);
        this.readOnlyImplementzString = generateReadOnlyImplementsString(getModel(), clazz);
        this.immutableImplementzString = generateImmutableImplementsString(getModel(), clazz);

        this.implementsList.addAll(generateImplementsList(model, clazz));
    }

    public static ModelType newInstance(Model model, Class<?> clazz, int typeId) {
        return new ModelType(model, clazz, typeId);
    }

    private void initProperties(Class<?> clazz) {

        if (!properties.isEmpty()) {
            throw new RuntimeException("Already initialized.");
        }

        List<Method> list = new ArrayList<Method>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().startsWith("get")
                    || (m.getName().startsWith("is")
                    && Objects.equals(m.getReturnType(), boolean.class))) {
                list.add(m);
            }
        }

        boolean hasPropertyWithoutCustomOrder = false;
        for (Method m : list) {
            Prop p = Prop.newInstance(this, m);

            properties.add(p);

            System.out.println();

            if(p.getCustomOrderIndex()!=null) {
                customPropertyOrderPresent = true;
            } else {
                hasPropertyWithoutCustomOrder = true;
            }
        }

        if(customPropertyOrderPresent && hasPropertyWithoutCustomOrder) {
            throw new RuntimeException("Model type '" + getTypeName() + "' has incomplete property order info " +
                    "(either annotate all or none of the properties)");
        }

        List<Prop> distinctProperties = Prop.filterDuplicateProps(properties, false);
        this.properties.clear();
        this.properties.addAll(distinctProperties);

        sortProperties(properties, customPropertyOrderPresent);
    }

    /**
     * Indicates whether a custom property order is present.
     * @return {@code true} if a custom property order is present; {@code false} otherwise
     */
    public boolean isCustomPropertyOrderPresent() {
        return customPropertyOrderPresent;
    }

    /**
     * Sorts properties based on custom order or alphabetically if no such order has been specified.
     * @param properties properties to sort
     * @param customPropertyOrder defines whether custom order is present
     */
    static void sortProperties(List<Prop> properties, boolean customPropertyOrder) {

        System.out.println("CUSTOM ORDER: " + customPropertyOrder);

        if(customPropertyOrder) {
            Collections.sort(properties, (p1, p2) -> p1.getCustomOrderIndex().compareTo(p2.getCustomOrderIndex()));
        } else {
            Collections.sort(properties, (p1, p2) -> p1.getName().compareTo(p2.getName()));
        }
    }

    private void initDelegations(Class<?> clazz) {

        if (!delegations.isEmpty()) {
            throw new RuntimeException("Already initialized.");
        }

        List<Method> list = new ArrayList<Method>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (!m.getName().startsWith("get")
                    && !m.getName().startsWith("is")
                    &&  !m.getName().startsWith("vmf")) {
                list.add(m);
            }
        }

        Collections.sort(list, (p1, p2) -> p1.getName().compareTo(p2.getName()));

        for(Method m : list) {
            DelegationInfo d = DelegationInfo.newInstance(model, m);

            if(d!=null) {
                delegations.add(d);
                methodDelegations.add(d);
            } else {
                throw new RuntimeException(
                        "Custom method '"
                                +m.getDeclaringClass().getName()+"."+m.getName()
                                +"(...)' does not define a delegation class.");
            }
        }

        // constructor delegations
        DelegationInfo cD = DelegationInfo.newInstance(model, clazz);
        if(cD!=null) {
            delegations.add(cD);
            constructorDelegations.add(cD);
        }
    }

    void initSyncInfos() {
        // resolve containment relations
        for (Prop p : properties) {
            p.initSyncInfo();
        }
    }

    void initContainments() {
        // resolve containment relations
        for (Prop p : properties) {
            p.initContainment();
        }

        propertiesWithoutCollectionsBasedContainment.addAll(
                propertiesWithoutCollectionsBasedContainment(this, properties));

        this.iface = Interface.newInstance(this);
        this.readOnlyInterface = ReadOnlyInterface.newInstance(this);
        this.readOnlyImplementation = ReadOnlyImplementation.newInstance(this);
    }

    void initImplements() {
        // init implements
        for (String implClsName : implementsList) {
            Optional<ModelType> type = model.resolveType(implClsName);

            if (!type.isPresent()) {
                throw new RuntimeException("Model types can only extend other model types."
                        + " Extending external type '" + implClsName + "' is not supported.");
            }

            implementz.add(type.get());
        }

        this.implementation = Implementation.newInstance(this);
    }

//    private void initImports(List<String> imports) {
//        if (!imports.isEmpty()) {
//            throw new RuntimeException("Already initialized.");
//        }
//
//        imports.addAll(properties.stream().map(p -> p.getPackageName()).
//                filter(pkg -> !pkg.isEmpty()).filter(pkg -> !"java.lang".equals(pkg)).
//                filter(pkg -> !getModel().getPackageName().equals(pkg)).map(imp -> imp + ".*").distinct().
//                collect(Collectors.toList()));
//    }

    public int getTypeId() {
        return typeId;
    }

    public Model getModel() {
        return model;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getFullTypeName() {
        return packageName + "." + typeName;
    }

    public Optional<Prop> resolveProp(String prop) {
        for (Prop p : properties) {
            if (Objects.equals(p.getName(), prop)) {
                return Optional.of(p);
            }
        }

        return Optional.empty();
    }

    public boolean extendsType(ModelType type) {

        if(type.getFullTypeName().equals(this.getFullTypeName())) return true;

        return getImplementz().stream().filter(t -> t.getFullTypeName().equals(type.getFullTypeName())).count() > 0;
    }

    private static List<String> generateImplementsList(Model model, Class<?> clazz) {
        List<String> implementz = new ArrayList<>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String ifsName = model.convertModelTypeToDestination(ifs);

            if (ifsName.startsWith(model.getPackageName())) {
                implementz.add(ifsName);
            } else {
                throw new RuntimeException("FIXME: in type '" + clazz.getName() + "' wrong pkg in impl: " + ifsName);
            }
        }

        return implementz;
    }

    private static String generateImplementsString(Model model, Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String ifsName = model.convertModelTypeToDestination(ifs);

            if (ifsName.startsWith(model.getPackageName())) {
                ext3nds.add(ifs.getSimpleName());
            }
        }

        return String.join(",", ext3nds);
    }

    private static String generateWritableImplementsString(Model model, Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String ifsName = model.convertModelTypeToDestination(ifs);

            if (ifsName.startsWith(model.getPackageName())) {
                ext3nds.add("Writable" + ifs.getSimpleName());
            }
        }

        return String.join(",", ext3nds);
    }

    private static String generateReadOnlyImplementsString(Model model, Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String ifsName = model.convertModelTypeToDestination(ifs);

            if (ifsName.startsWith(model.getPackageName())) {
                ext3nds.add("ReadOnly" + ifs.getSimpleName());
            }
        }

        return String.join(",", ext3nds);
    }


    private static String generateImmutableImplementsString(Model model, Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String ifsName = model.convertModelTypeToDestination(ifs);

            if (ifsName.startsWith(model.getPackageName())) {
                if (ifs.getAnnotation(Immutable.class) != null) {
                    ext3nds.add("" + ifs.getSimpleName());
                } else {
                    ext3nds.add("ReadOnly" + ifs.getSimpleName());
                }
            }
        }

        return String.join(",", ext3nds);
    }

    private static String generateExtendsString(Model model, Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String implClsName = model.convertModelPackageToDestination(
                    TypeUtil.getPackageName(ifs)) + "."
                    + VMFEngineProperties.VMF_IMPL_PKG_EXT + "." + ifs.getSimpleName()
                    + VMFEngineProperties.VMF_IMPL_CLASS_EXT;

            ext3nds.add(implClsName);
        }

        return String.join(",", ext3nds);
    }

    public String getExtendsString() {
        if (ext3ndsString.isEmpty()) {
            return "";
        } else {
            return "extends " + ext3ndsString;
        }
    }

    public String getImplementsString() {

        if (implementzString.isEmpty()) {
            return "";
        } else {
            return ", " + implementzString;
        }
    }

    public String getWritableImplementsString() {

        if (writableImplementzString.isEmpty()) {
            return "";
        } else {
            return ", " + writableImplementzString;
        }
    }

    public String getReadOnlyImplementsString() {

        if (readOnlyImplementzString.isEmpty()) {
            return "";
        } else {
            return ", " + readOnlyImplementzString;
        }
    }

    public String getImmutableImplementsString() {

        if (immutableImplementzString.isEmpty()) {
            return "";
        } else {
            return ", " + immutableImplementzString;
        }
    }

    public String getPackageName() {
        return packageName;
    }

    public List<Prop> getProperties() {
        return properties;
    }

    public List<String> getImports() {
        return imports;
    }

    public List<DelegationInfo> getDelegations() {
        return delegations;
    }

    public List<DelegationInfo> getMethodDelegations() {
        return methodDelegations;
    }

    public List<DelegationInfo> getConstructorDelegations() {
        return constructorDelegations;
    }

    /**
     * Indicates whether the {@link java.lang.Object#equals(Object)} is delegated to a custom
     * implementation.
     * @return {@code true} if the {@link java.lang.Object#equals(Object)} is delegated; {@code false}
     * otherwise
     */
    public boolean isEqualsMethodDelegated() {
        return getImplementation().getDelegations().stream().
                filter(d->"equals".equals(d.getMethodName())).
                filter(d->d.getParamTypes().size()==1).
                filter(d->"java.lang.Object".equals(d.getParamTypes().get(0))).
                filter(d->"boolean".equals(d.getReturnType())).count() > 0;
    }

    /**
     * Indicates whether the {@link Object#hashCode()} is delegated to a custom
     * implementation.
     * @return {@code true} if the {@link Object#hashCode()} is delegated; {@code false}
     * otherwise
     */
    public boolean isHashCodeMethodDelegated() {
        return getImplementation().getDelegations().stream().
                filter(d->"hashCode".equals(d.getMethodName())).
                filter(d->d.getParamTypes().isEmpty()).
                filter(d->"int".equals(d.getReturnType())).count() > 0;
    }

    /**
     * Indicates whether the {@link Object#toString()} is delegated to a custom
     * implementation.
     * @return {@code true} if the {@link Object#toString()} is delegated; {@code false}
     * otherwise
     */
    public boolean isToStringMethodDelegated() {
        return getImplementation().getDelegations().stream().
                filter(d->"toString".equals(d.getMethodName())).
                filter(d->d.getParamTypes().isEmpty()).
                filter(d->"java.lang.String".equals(d.getReturnType())).count() > 0;
    }

    /**
     * Indicates whether the {@link java.lang.Object#clone()} is delegated to a custom
     * implementation.
     * @return {@code true} if the {@link java.lang.Object#clone()} is delegated; {@code false}
     * otherwise
     */
    public boolean isCloneMethodDelegated() {
        return getImplementation().getDelegations().stream().
                filter(d->"clone".equals(d.getMethodName())).
                filter(d->d.getParamTypes().isEmpty()).count() > 0;
    }

    public List<ModelType> getImplementz() {
        return implementz;
    }

    public Implementation getImplementation() {
        return implementation;
    }

    public boolean isImmutable() {
        return immutable;
    }

    public boolean isInterfaceOnly() {
        return interfaceOnly;
    }

    private static final Map<String, String> primitiveToBoxedTypeNames = new HashMap<>();

    public static String primitiveToBoxedType(String typeName) {

        if (primitiveToBoxedTypeNames.isEmpty()) {
            primitiveToBoxedTypeNames.put("int", Integer.class.getSimpleName());
            primitiveToBoxedTypeNames.put("long", Integer.class.getSimpleName());
            primitiveToBoxedTypeNames.put("float", Float.class.getSimpleName());
            primitiveToBoxedTypeNames.put("bool", Boolean.class.getSimpleName());
            primitiveToBoxedTypeNames.put("char", Character.class.getSimpleName());
            primitiveToBoxedTypeNames.put("byte", Byte.class.getSimpleName());
            primitiveToBoxedTypeNames.put("void", Void.class.getSimpleName());
            primitiveToBoxedTypeNames.put("short", Short.class.getSimpleName());
        }

        return primitiveToBoxedTypeNames.containsKey(typeName) ? primitiveToBoxedTypeNames.get(typeName) : typeName;
    }

    public Interface getInterface() {
        return iface;
    }

    public ReadOnlyInterface getReadOnlyInterface() {
        return readOnlyInterface;
    }

    public ReadOnlyImplementation getReadOnlyImplementation() {
        return readOnlyImplementation;
    }

    public List<Prop> getPropertiesWithoutCollectionsBasedContainment() {
        return propertiesWithoutCollectionsBasedContainment;
    }

    public static List<Prop> propertiesWithoutCollectionsBasedContainment(ModelType type, List<Prop> properties) {
        Predicate<Prop> isContainmentProp = p -> p.isContainmentProperty();
        Predicate<Prop> isCollectionType = p -> p.getPropType() == PropType.COLLECTION;
        Predicate<Prop> oppositeIsCollectionType = p -> p.isContainmentProperty()
                && p.getContainmentInfo().getOpposite().getPropType() == PropType.COLLECTION;

        return properties.stream().
                filter(p -> isContainmentProp.and(isCollectionType).negate().test(p)).
                //filter(oppositeIsCollectionType.negate()).
                        collect(Collectors.toList());
    }

    public void initPropIds() {
        int i = 0;
        for(Prop p : getImplementation().getProperties()) {
            p.setPropId(i);
            i++;
        }
    }
}
