package eu.mihosoft.vmf.core;


import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by miho on 06.01.2017.
 */
public class ModelType {

    private final String packageName;
    private final String typeName;

    private final List<Prop> properties = new ArrayList<>();

    private final List<String> imports = new ArrayList<>();

    private final Model model;
    private final Interface iface;
    private final WritableInterface writableInterface;
    private final Implementation implementation;

    private ModelType(Model model, Class<?> clazz) {
        this.model = model;
        this.packageName = clazz.getPackage().getName();
        this.typeName = clazz.getSimpleName();

        initProperties(clazz);

        initImports(imports);

        this.iface = Interface.newInstance(this);
        this.writableInterface = WritableInterface.newInstance(this);
        this.implementation = Implementation.newInstance(this);
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

        // resolve containment relations
        for (Prop p : properties) {
            p.initContainment();
        }
    }

    private void initImports(List<String> imports) {
        if (!imports.isEmpty()) {
            throw new RuntimeException("Already initialized.");
        }

        imports.addAll(properties.stream().map(p->p.getPackageName()).
                filter(pkg->!pkg.isEmpty()).filter(pkg->!"java.lang".equals(pkg)).distinct().
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
            System.out.println("name: " + p.getName() + " == " + prop);
            if (Objects.equals(p.getName(),prop)) {
                return Optional.of(p);
            }
        }

        return Optional.empty();
    }

    public static List<String> getExtends(Class<?> clazz) {
        List<String> ext3nds = new ArrayList<String>();
        for (Class<?> ifs : clazz.getInterfaces()) {
            ext3nds.add(ifs.getName());
        }
        return ext3nds;
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
}
