package eu.mihosoft.vmf.core;

import java.util.*;

public class Model {

    private final String packageName;
    private Map<String, ModelType> types = new HashMap<>();
    private Class<?>[] interfaces;

    public static Model newInstance(Class<?>... interfaces) {
        return new Model(interfaces);
    }

    private Model(Class<?>... interfaces) {

        if(interfaces == null || interfaces.length == 0) {
            throw new IllegalArgumentException(
                    "At least one interface is required for building a valid model.");
        }

        this.interfaces = interfaces;
        String modelPkgName = interfaces[0].getPackage().getName();

        if (!modelPkgName.endsWith(".vmfmodel")) {
            throw new IllegalArgumentException(
                    "Model interfaces should be in subpackage 'vmfmodel'." +
                            " Found model interfaces in '"
                            + modelPkgName + "'.");
        }

        this.packageName = modelPkgName.substring(
                0,modelPkgName.length()-".vmfmodel".length());

        Set<String> packages = new HashSet<String>();
        for (Class<?> clzz : interfaces) {
            if (!clzz.isInterface())
                throw new IllegalArgumentException(
                        "Model may only contain interfaces.");
            initType(clzz);
            packages.add(clzz.getPackage().getName());

        }
        if (packages.size() > 1) {
            throw new IllegalArgumentException(
                    "All interfaces must be from same package, but found "
                            + packages);
        }

        for(ModelType t : types.values()) {
            t.initContainments();
        }
    }

    public String getPackageName() {
        return packageName;
    }

    public ModelType initType(Class<?> clazz) {

        ModelType t = ModelType.newInstance(this, clazz);
        types.put(convertModelTypeToDestination(clazz), t);

        return t;
    }

    public Collection<ModelType> getTypes() {
        return Collections.unmodifiableCollection(types.values());
    }

//    public Optional<ModelType> resolveType(Class<?> clazz) {
//        if (types.containsKey(clazz.getName()))
//            return Optional.of(types.get(clazz.getName()));
//        else {
//            if (Arrays.stream(interfaces).anyMatch(ifc -> clazz.equals(ifc)))
//                return Optional.of(initType(clazz));
//            else
//                return Optional.empty();
//        }
//    }

    public Optional<ModelType> resolveType(String clazzName) {
        return Optional.ofNullable(types.get(clazzName));
    }

    /**
     * Resolves the specified opposite property of the given model type.
     *
     * @param type         model type
     * @param oppositeProp fully qualified name of the opposite property of the given type, e.g.,
     *                     '<em>eu.mihosoft.tutorial.MyType.myProp</em>' or simplified, i.e., without package if the
     *                     package matches the current model package, e.g., '<em>MyType.myProp</em>'
     * @return resolved opposite property or {@code Optional<Prop>.empty()} if the specified property does not exist
     */
    public Optional<Prop> resolveOppositeOf(ModelType type, String oppositeProp) {

        Optional<Prop> prop = type.resolveProp(oppositeProp);

        if (prop.isPresent()) {
            return prop;
        }

        String[] typeNameParts = oppositeProp.split("\\.");

        // we tried to resolveType with this type already
        if (typeNameParts.length <= 1) {
            return Optional.empty();
        }

        // we specified property without package name. we assume it is in the current package
        if(typeNameParts.length==2) {
            typeNameParts = (getPackageName()+"."+oppositeProp).split("\\.");
        }

        String typeName = "";

        for (int i = 0; i < typeNameParts.length - 1; i++) {
            typeName += typeNameParts[i];

            if(i < typeNameParts.length - 2) {
                typeName+=".";
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
            if (convertModelPackageToDestination(clazz.getName()).equals(type))
                return true;
            for (Class<?> ifc : clazz.getInterfaces()) {
                if (convertModelPackageToDestination(ifc.getName()).equals(type))
                    return true;
            }
        }
        return false;
    }

    public String convertModelPackageToDestination(String srcPkg) {
        if(Objects.equals(getPackageName()+".vmfmodel",srcPkg)) {
            return getPackageName();
        } else {
            return srcPkg;
        }
    }

    public String convertModelTypeToDestination(Class<?> srcType) {
        String srcPackage = "";

        if (srcType.getPackage()!=null) {
            srcPackage = srcType.getPackage().getName();
        }

        if(Objects.equals(getPackageName()+".vmfmodel",srcPackage)) {
            return getPackageName()+"."+srcType.getSimpleName();
        } else {
            return srcType.getName();
        }
    }
}
