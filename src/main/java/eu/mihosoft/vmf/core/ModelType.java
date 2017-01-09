package eu.mihosoft.vmf.core;


import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 */
public class ModelType {

    private final String packageName;
    private final String typeName;

    private final List<Prop> properties = new ArrayList<>();
    private final List<Prop> propertiesWithoutCollectionsBasedContainment = new ArrayList<>();

    private final List<String> imports = new ArrayList<>();

    private final Model model;
    private final Interface iface;
    private WritableInterface writableInterface;
    private Implementation implementation;

    private final String ext3ndsString;
    private final String implementzString;
    private final List<String> implementsList = new ArrayList<>();
    private final String writableImplementzString;

    private final List<ModelType> implementz = new ArrayList<>();



    private ModelType(Model model, Class<?> clazz) {
        this.model = model;

        this.packageName = model.getPackageName();

        this.typeName = clazz.getSimpleName();

        initProperties(clazz);

        initImports(imports);

        this.iface = Interface.newInstance(this);

        this.ext3ndsString = generateExtendsString(getModel(), clazz);
        this.implementzString = generateImplementsString(getModel(), clazz);
        this.writableImplementzString = generateWritableImplementsString(getModel(), clazz);

        this.implementsList.addAll(generateImplementsList(model,clazz));
    }

    public static ModelType newInstance(Model model, Class<?> clazz) {
        return new ModelType(model, clazz);
    }

    private void initProperties(Class<?> clazz) {

        if (!properties.isEmpty()) {
            throw new RuntimeException("Already initialized.");
        }

        List<Method> list = new ArrayList<Method>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().startsWith("get")) {
                list.add(m);
            }
        }

        for (Method m : list) {
            Prop p = Prop.newInstance(this,m);

            properties.add(p);
        }



    }

    void initContainments() {
        // resolve containment relations
        for (Prop p : properties) {
            p.initContainment();
        }

        propertiesWithoutCollectionsBasedContainment.addAll(
                propertiesWithoutCollectionsBasedContainment(this, properties));

        this.writableInterface = WritableInterface.newInstance(this);
    }

    void initImplements() {
        // init implements
        for(String implClsName : implementsList) {
            Optional<ModelType> type = model.resolveType(implClsName);

            if(!type.isPresent()) {
                throw new RuntimeException("Model types can only extend other model types." +
                        " Extending external type '" + implClsName + "' is not supported.");
            }

            implementz.add(type.get());
        }

        this.implementation = Implementation.newInstance(this);
    }

    private void initImports(List<String> imports) {
        if (!imports.isEmpty()) {
            throw new RuntimeException("Already initialized.");
        }

        imports.addAll(properties.stream().map(p->p.getPackageName()).
                filter(pkg->!pkg.isEmpty()).filter(pkg->!"java.lang".equals(pkg)).
                filter(pkg->!getModel().getPackageName().equals(pkg)).map(imp->imp+".*").distinct().
                collect(Collectors.toList()));
    }

    public Model getModel() {
        return model;
    }

    public String getTypeName() {
        return typeName;
    }

    public String getFullTypeName() {
        return packageName+"."+typeName;
    }

    public Optional<Prop> resolveProp(String prop) {
        for(Prop p : properties) {
            if (Objects.equals(p.getName(),prop)) {
                return Optional.of(p);
            }
        }

        return Optional.empty();
    }

    private static List<String> generateImplementsList(Model model, Class<?> clazz) {
        List<String> implementz = new ArrayList<>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String ifsName = model.convertModelTypeToDestination(ifs);

            if (ifsName.startsWith(model.getPackageName())) {
                implementz.add(ifsName);
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

        return String.join(",",ext3nds);
    }

    private static String generateWritableImplementsString(Model model, Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String ifsName = model.convertModelTypeToDestination(ifs);

            if (ifsName.startsWith(model.getPackageName())) {
                ext3nds.add("Writable"+ifs.getSimpleName());
            }
        }

        return String.join(",",ext3nds);
    }

    private static String generateExtendsString(Model model, Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {

            String implClsName = model.convertModelPackageToDestination(
                    ifs.getPackage().getName())+"."
                    +VMFEngineProperties.VMF_IMPL_PKG_EXT+"."+ifs.getSimpleName()
                    + VMFEngineProperties.VMF_IMPL_CLASS_EXT;

            ext3nds.add(implClsName);
        }

        return String.join(",",ext3nds);
    }

    public String getExtendsString() {
        if(ext3ndsString.isEmpty()) {
            return "";
        } else
            return "extends "+ ext3ndsString;
    }

    public String getImplementsString() {

        if(implementzString.isEmpty()) {
            return "";
        } else

        return ", " + implementzString;
    }

    public String getWritableImplementsString() {

        if(writableImplementzString.isEmpty()) {
            return "";
        } else

            return ", " + writableImplementzString;
    }



    static String propertyNameFromGetter(Method getterMethod) {
        String name = getterMethod.getName().substring("get".length());
        name = name.substring(0, 1).toLowerCase() + name.substring(1);
        return name;
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

    public List<ModelType> getImplementz() {
        return implementz;
    }

    public Implementation getImplementation() {
        return implementation;
    }

    private static final Map<String,String> primitiveToBoxedTypeNames = new HashMap<>();

    public static String primitiveToBoxedType(String typeName) {

        if(primitiveToBoxedTypeNames.isEmpty()){
            primitiveToBoxedTypeNames.put("int", Integer.class.getSimpleName() );
            primitiveToBoxedTypeNames.put("float", Float.class.getSimpleName() );
            primitiveToBoxedTypeNames.put("bool", Boolean.class.getSimpleName() );
            primitiveToBoxedTypeNames.put("char", Character.class.getSimpleName() );
            primitiveToBoxedTypeNames.put("byte", Byte.class.getSimpleName() );
            primitiveToBoxedTypeNames.put("void", Void.class.getSimpleName() );
            primitiveToBoxedTypeNames.put("short", Short.class.getSimpleName() );
        }

        return primitiveToBoxedTypeNames.get(typeName);
    }

    public WritableInterface getWritableInterface() {
        return writableInterface;
    }

    public List<Prop> getPropertiesWithoutCollectionsBasedContainment() {
        return propertiesWithoutCollectionsBasedContainment;
    }

    public static List<Prop> propertiesWithoutCollectionsBasedContainment(ModelType type, List<Prop> properties) {
        Predicate<Prop> isContainmentProp = p->p.isContainmentProperty();
        Predicate<Prop> isCollectionType = p->p.getPropType()== PropType.COLLECTION;
        Predicate<Prop> oppositeIsCollectionType = p->p.isContainmentProperty()
                &&p.getContainmentInfo().getOpposite().getPropType()==PropType.COLLECTION;

        return properties.stream().
                filter(p->isContainmentProp.and(isCollectionType).negate().test(p)).
                filter(oppositeIsCollectionType.negate()).
                collect(Collectors.toList());
    }
}
