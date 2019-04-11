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

import java.util.*;
import java.util.stream.Collectors;

/**
 * 
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
public class Model {

    private final String packageName;
    private final Map<String, ModelType> types = new HashMap<>();
    private Class<?>[] interfaces;

    public static Model newInstance(Class<?>... interfaces) {
        return new Model(interfaces);
    }

    private Model(Class<?>... interfaces) {

        if (interfaces == null || interfaces.length == 0) {
            throw new IllegalArgumentException(
                    "At least one interface is required for building a valid model.");
        }

        this.interfaces = interfaces;
        String modelPkgName = TypeUtil.getPackageName(interfaces[0]);

        if (!modelPkgName.endsWith(".vmfmodel")) {
            throw new IllegalArgumentException(
                    "Model interfaces should be in subpackage 'vmfmodel'."
                            + " Found model interfaces in '"
                            + modelPkgName + "'.");
        }

        this.packageName = modelPkgName.substring(
                0, modelPkgName.length() - ".vmfmodel".length());

        // PASS 0
        Set<String> packages = new HashSet<String>();
        int typeId = 0;
        for (Class<?> clzz : interfaces) {
            if (!clzz.isInterface()) {
                throw new IllegalArgumentException(
                        "Model may only contain interfaces.");
            }
            initType(clzz, typeId);
            typeId += 2 /*type stride (number of types of types, i.e., type categories such as read-only and default)*/;
            packages.add(TypeUtil.getPackageName(clzz));

        }
        if (packages.size() > 1) {
            throw new IllegalArgumentException(
                    "All interfaces must be from same package, but found "
                            + packages);
        }

        // PASS 1
        for (ModelType t : types.values()) {
            t.initContainments();
        }

        // PASS 2
        for (ModelType t : types.values()) {
            t.initCrossRefInfos();
        }

        // PASS 3
        for (ModelType t : types.values()) {
            t.initImplements();
        }

        // PASS 4
        for(ModelType t : types.values()) {
            t.initSyncInfos();
            t.initPropIds();
        }

        // PASS 5
        for (ModelType t : types.values()) {
            t.getImplementation().initPropertiesImportsAndDelegates();
        }

        // PASS 6
        for (ModelType t : types.values()) {
            t.getInterface().initProperties();
            t.initAllInheritedTypes();
        }

        // PASS 7
        for (ModelType t : types.values()) {

            if(t.isHashCodeMethodDelegated()!=t.isEqualsMethodDelegated()) {
                if(t.isHashCodeMethodDelegated()) {
                    throw new RuntimeException("If 'int hashCode()' is delegated, 'boolean equals(Object o)' must be delegated too.");
                } else {
                    throw new RuntimeException("If 'boolean equals(Object o)' is delegated, 'int hashCode()' must be delegated too.");
                }
            }

            if (!t.isImmutable()) {
                for (ModelType iType : t.getAllInheritedTypes()) {
                    if (iType.isImmutable()) {
                        throw new RuntimeException("Mutable type '" + t.getFullTypeName()
                                + "' cannot extend immutable type '"
                                + iType.getFullTypeName() + "'.");
                    }
                }
                for (Prop p: t.getProperties()) {
                    if(p.getType()!=null && p.getType().isImmutable()) {
                        if (p.isContainer()) {
                            throw new RuntimeException("Immutable type '" + t.getFullTypeName()
                                    + "' cannot be contained in type '"
                                    + p.getType().getFullTypeName() + "' (parameter: '" + p.getName()+"').");
                        } else if (p.isContained()) {
                            throw new RuntimeException("Immutable type '" + t.getFullTypeName()
                                    + "' cannot be container of type '"
                                    + p.getType().getFullTypeName() + "' (parameter: '" + p.getName()+"').");
                        }
                    }

                    if(!t.isInterfaceOnly() && p.isGetterOnly()) {
                        throw new RuntimeException("Mutable type '" + t.getFullTypeName()
                                + "' cannot declare getter-only parameter '" + p.getName()
                                +"'. Type should be an immutable or an interface-only type.");
                    }
                }
            } else {

                for (ModelType iType : t.getAllInheritedTypes()) {

                    boolean accepted = iType.isImmutable() || iType.isInterfaceOnlyWithGettersOnly();

                    if (!accepted) {
                        throw new RuntimeException("Immutable type '" + t.getFullTypeName()
                                + "' cannot extend mutable type '"
                                + iType.getFullTypeName() + "'.");
                    }
                }

                for (Prop p : t.getImplementation().getProperties()) {
                    if (p.getType() != null) {  
                        if (!p.getType().isImmutable()) {
                            throw new RuntimeException("Immutable type '" + t.getFullTypeName()
                                    + "' cannot have properties of mutable type '"
                                    + p.getType().getFullTypeName() + "'.");
                        }
                    } else if (p.isCollectionType()) {
                        if (p.getGenericType() != null) {
                            if (!p.getGenericType().isImmutable()) {
                                throw new RuntimeException("Immutable type '" + t.getFullTypeName()
                                        + "' cannot have collection properties with mutable element type '"
                                        + p.getGenericType().getFullTypeName() + "'.");
                            }
                        }
                    }

                    if (p.isContainer()) {
                        throw new RuntimeException("Immutable type '" + t.getFullTypeName()
                                + "' cannot be contained in type '"
                                + p.getType().getFullTypeName() + "' (parameter: '" + p.getName()+"').");
                    } else if (p.isContained()) {
                        throw new RuntimeException("Immutable type '" + t.getFullTypeName()
                                + "' cannot be container of type '"
                                + p.getType().getFullTypeName() + "' (parameter: '" + p.getName()+"').");
                    }
                }
            }

        } // end pass 7

    }

    /**
     * Returns all properties of this model (searches in all properties of all model types) that contain instance of the
     * specified type. This includes types that extend this type.
     * @param type type to analyze
     * @return properties that match the aforementioned criterions
     */
    public List<Prop> findAllPropsThatContainType(ModelType type) {

        List<ModelType> allTypes = getTypes();
        List<Prop> result = new ArrayList<>();

        // check whether this model contains entities with containment
        // properties that declare no opposite and 
        for(ModelType t : allTypes) {
            for(Prop p : t.getProperties()) {
                if(
                    p.isContainmentProperty()
                    && p.getContainmentInfo().isWithoutOpposite()
                    // && p.getContainmentInfo().getContainmentType()==ContainmentType.CONTAINED
                    && p.getType().extendsType(type)) {
                    result.add(p);
                }
            }
        }

        return result;
    }

    public String getPackageName() {
        return packageName;
    }

    ModelType initType(Class<?> clazz, int typeId) {

        ModelType t = ModelType.newInstance(this, clazz, typeId);
        types.put(convertModelTypeToDestination(clazz), t);

        return t;
    }

    public List<ModelType> getTypes() {
        // TODO 11.04.2019 why do we sort over and over again?

        List<ModelType> typeList = new ArrayList<>(types.values());

        Collections.sort(typeList,
                Comparator.comparing(ModelType::getFullTypeName));

        return Collections.unmodifiableList(typeList);
    }

    public Optional<ModelType> resolveType(String clazzName) {
        return Optional.ofNullable(types.get(clazzName));
    }

    public Optional<Class<?>> resolveExternalType(String fullClassName) {
        // TODO introduce classloader for external types
        try {
            return Optional.of(getClass().getClassLoader().loadClass(fullClassName));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Resolves the specified opposite property of the given model type.
     *
     * @param type         model type
     * @param oppositeProp fully qualified name of the opposite property of the
     *                     given type, e.g., '<em>eu.mihosoft.tutorial.MyType.myProp</em>' or
     *                     simplified, i.e., without package if the package matches the current
     *                     model package, e.g., '<em>MyType.myProp</em>'
     * @return resolved opposite property or {@code Optional<Prop>.empty()} if
     * the specified property does not exist
     */
    public Optional<Prop> resolveOppositeOf(ModelType type, String oppositeProp) {

        String[] typeNameParts = oppositeProp.split("\\.");

        // we tried to resolveType with this type already
        if (typeNameParts.length <= 1) {
            return Optional.empty();
        }

        // we specified property without package name. we assume it is in the current package
        if (typeNameParts.length == 2) {
            typeNameParts = (getPackageName() + "." + oppositeProp).split("\\.");
        }

        String typeName = "";

        for (int i = 0; i < typeNameParts.length - 1; i++) {
            typeName += typeNameParts[i];

            if (i < typeNameParts.length - 2) {
                typeName += ".";
            }
        }

        String propName = typeNameParts[typeNameParts.length - 1];

        Optional<ModelType> other = resolveType(typeName);

        if (other.isPresent()) {
            return other.get().resolveProp(propName);
        }

        return Optional.empty();
    }


    public boolean isModelType(String type) {
        for (Class<?> clazz : interfaces) {
            if (convertModelPackageToDestination(clazz.getName()).equals(type)) {
                return true;
            }
            for (Class<?> ifc : clazz.getInterfaces()) {
                if (convertModelPackageToDestination(ifc.getName()).equals(type)) {
                    return true;
                }
            }
        }
        return false;
    }

    String convertModelPackageToDestination(String srcPkg) {
        if (Objects.equals(getPackageName() + ".vmfmodel", srcPkg)) {
            return getPackageName();
        } else {
            return srcPkg;
        }
    }

    String convertModelTypeToDestination(Class<?> srcType) {
        String srcPackage =  TypeUtil.getPackageName(srcType);

        if (Objects.equals(getPackageName() + ".vmfmodel", srcPackage)) {
            return getPackageName() + "." + srcType.getSimpleName();
        } else {
            return srcType.getName();
        }
    }

    /**
     * Returns all types that implement the specified type.
     * @param mT type
     * @return all types that implement the specified type
     */
    public List<ModelType> getAllTypesThatImplement(ModelType mT) {
        return getTypes().stream().filter(t->t.getImplementz().contains(mT)).distinct().collect(Collectors.toList());
    }
}
