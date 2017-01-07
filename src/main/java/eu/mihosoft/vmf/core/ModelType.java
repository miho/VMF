package eu.mihosoft.vmf.core;


import java.lang.reflect.Method;
import java.util.*;

/**
 * Created by miho on 06.01.2017.
 */
public class ModelType {

    private final String packageName;
    private final String typeName;

    private final List<Prop> properties = new ArrayList<>();

    private final Model model;
    private final Interface iface;
    private final WritableInterface writableIface;
    private final Implementation implementation;

    private ModelType(Model model, Class<?> clazz) {
        this.model = model;
        this.packageName = clazz.getPackage().getName();
        this.typeName = clazz.getSimpleName();

        initProperties(clazz);

        this.iface = Interface.newInstance(this);
        this.writableIface = WritableInterface.newInstance(this);
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
}
