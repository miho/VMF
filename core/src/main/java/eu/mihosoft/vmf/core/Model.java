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
    private ModelConfig modelConfig;
    private final Map<String, ModelType> types = new HashMap<>();
    private final Map<String, ExternalType> extTypes = new HashMap<>();
    private final List<Class<?>> interfaces = new ArrayList<>();

    public static Model newInstance(Class<?>... interfaces) {
        return new Model(interfaces);
    }

    private Model(Class<?>... interfacesRaw) {

        if (interfacesRaw == null || interfacesRaw.length == 0) {
            throw new IllegalArgumentException("At least one interface is required for building a valid model.");
        }

        String modelPkgName = TypeUtil.getPackageName(interfacesRaw[0]);

        if (!modelPkgName.endsWith(".vmfmodel")) {
            throw new IllegalArgumentException("Model interfaces should be in subpackage 'vmfmodel'."
                    + " Found model interfaces in '" + modelPkgName + "'.");
        }

        this.packageName = modelPkgName.substring(0, modelPkgName.length() - ".vmfmodel".length());



        for (Class<?> iWithPkgAnn : interfacesRaw) {
            // initialize model config
            VMFModel modelAnn = iWithPkgAnn.getAnnotation(VMFModel.class);
            if(modelAnn!=null) {

                if(this.modelConfig!=null) {
                    throw new RuntimeException("VMFModel annotation can only be defined once per model. Preferably at the top of the file.");
                }

                this.modelConfig = ModelConfig.fromAnnotation(modelAnn);
            }
        }

        // if no annotation has been found, set config to default
        if(modelConfig==null) {
            this.modelConfig = ModelConfig.fromAnnotation(null);
        }


        // PASS 0.0
        this.interfaces.clear();
        for (Class<?> clzz : interfacesRaw) {
            ExternalType externalType = clzz.getAnnotation(ExternalType.class);
            if(externalType!=null) {
                extTypes.put(clzz.getSimpleName(), externalType);
            } else {
                this.interfaces.add(clzz);
            }
        }

        // PASS 0.1
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

        // PASS 5
        for (ModelType t : types.values()) {
            t.getImplementation().initPropertiesImportsAndDelegates();
        }

        // PASS 4
        for(ModelType t : types.values()) {
            t.initSyncInfos();
            t.initPropIds();
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

    public ModelConfig getModelConfig() {
        return this.modelConfig;
    }

    /**
     * Returns all properties of this model (searches in all properties of all model types) that contain instance of the
     * specified type and have no explicit opposite. This includes types that extend this type.
     * @param type type to analyze
     * @return properties that match the aforementioned criterions
     */
    List<Prop> findAllPropsThatContainTypeWithoutOpposite(ModelType type) {
        return findAllPropsThatContainType(type, false);
    }

    /**
     * Returns all properties of this model (searches in all properties of all model types) that contain instance of the
     * specified type and have no explicit opposite. This includes types that extend this type.
     * @param type type to analyze
     * @param withOpposite indicates whether to search for containers with or without opposites
     * @return properties that match the aforementioned criterions
     */
    List<Prop> findAllPropsThatContainType(ModelType type, boolean withOpposite) {

        List<Prop> result = new ArrayList<>();
        if(type==null) return result;
        
        List<ModelType> allTypes = getTypes();

        // check whether this model contains entities with containment
        // properties that declare no opposite and 
        for(ModelType t : allTypes) {
            for(Prop p : t.getProperties()) {

                // if p is a single valued parameter then obtain the type directly
                // if it is a collection param then get the generic type
                ModelType pType;
                if(p.getType()!=null) {
                    pType = p.getType();
                } else if (p.isCollectionType() && p.getGenericType()!=null) {
                    pType = p.getGenericType();
                } else {
                    pType = null;
                }

                if(pType==null) continue;

                // add the parameter if its type is a model type, is container
                // and no opposite has been specified and if the parameter type
                // and the specified type have a common descendent or ancestor
                if(    p.isContainmentProperty()
                    && (
                           (!withOpposite && p.getContainmentInfo().isWithoutOpposite())
                            ||
                           (withOpposite && !p.getContainmentInfo().isWithoutOpposite()) 
                       )
                    && p.getContainmentInfo().getContainmentType()==ContainmentType.CONTAINED
                    && (pType.extendsType(type) || type.extendsType(pType))) {
                    result.add(p);
                }
            }
        }
        return result;

        // // version with streams & lambdas
        // return allTypes.stream().flatMap(t->t.getProperties().stream()).
        //     filter(p->p.isContainmentProperty()).
        //     filter(p->p.getContainmentInfo().isWithoutOpposite()).
        //     filter(p->p.getContainmentInfo().getContainmentType()==ContainmentType.CONTAINED).
        //     filter(p->
        //       p.getType()!=null && (p.getType().extendsType(type)
        //       ||type.extendsType(p.getType()))
        //     ).collect(Collectors.toList());
    }

    /**
     * Determines whether the specified type is contained via {@code @Contains} without opposite.
     * @param type type to check
     * @return {@code true} if the specified type is contained without opposite; {@code false} otherwise
     */
    boolean isContainedWithoutOpposite(ModelType type) {
        return !findAllPropsThatContainTypeWithoutOpposite(type).isEmpty();
    }

    /**
     * Determines whether the specified type is contained via {@code @Contains} (with or without opposite).
     * @param type type to check
     * @return {@code true} if the specified type is contained; {@code false} otherwise
     */
    boolean isContained(ModelType type) {
        boolean containedWithoutOpposite = !findAllPropsThatContainTypeWithoutOpposite(type).isEmpty();
        boolean containedWithOpposite = !findAllPropsThatContainType(type, true).isEmpty();

        return containedWithOpposite || containedWithoutOpposite;
    }

        /**
     * Determines whether the specified type is contained via {@code @Contains} with opposite.
     * @param type type to check
     * @return {@code true} if the specified type is contained with opposite; {@code false} otherwise
     */
    boolean isContainedWithOpposite(ModelType type) {
        boolean containedWithoutOpposite = !findAllPropsThatContainTypeWithoutOpposite(type).isEmpty();
        boolean containedWithOpposite = !findAllPropsThatContainType(type, true).isEmpty();

        return containedWithOpposite || containedWithoutOpposite;
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
            if (convertModelPackageToDestination(clazz.getSimpleName(), TypeUtil.getPackageName(clazz)).equals(type)) {
                return true;
            }
            for (Class<?> ifc : clazz.getInterfaces()) {
                if (convertModelPackageToDestination(ifc.getSimpleName(),TypeUtil.getPackageName(ifc)).equals(type)) {
                    return true;
                }
            }
        }

        return false;
    }

    String convertModelPackageToDestination(String simpleName, String srcPkg) {

        if (Objects.equals(getPackageName() + ".vmfmodel", srcPkg)) {

            String packageName =  getPackageName();
            ExternalType externalType = extTypes.get(simpleName);
            if(externalType!=null) {
                packageName = externalType.pkgName();
            }

            return packageName;
        } else {
            return srcPkg;
        }
    }

    String convertModelTypeToDestination(Class<?> srcType) {
        String srcPackage =  TypeUtil.getPackageName(srcType);
        String simpleName = srcType.getSimpleName();

        ExternalType externalType = extTypes.get(simpleName);
        if(externalType!=null) {
            srcPackage = externalType.pkgName();
        }

        if (Objects.equals(getPackageName() + ".vmfmodel", srcPackage)) {
            return getPackageName() + "." + simpleName;
        } else {
            return srcPackage + "." + simpleName;
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

    public boolean isExternalType(String implClsName) {
        String simpleName = TypeUtil.getShortNameFromFullClassName(implClsName);
        ExternalType externalType = extTypes.get(simpleName);
        return externalType!=null;
    }

}
