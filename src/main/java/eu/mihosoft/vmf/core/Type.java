package eu.mihosoft.vmf.core;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Created by miho on 05.01.2017.
 */
public class Type {
    private final Class<?> clazz;
    private final Model model;

    private Type[] ext3nds;

    // properties (member variables + getter & setter methods)
    private final List<Property> properties = new ArrayList<>();
    private final List<Property> propertiesReadOnly =
            Collections.unmodifiableList(properties);

    // full type name (including package name, e.g. eu.mihosoft.vmf.MyType)
    private final String fullTypeName;

    // full package name e.g. eu.mihosoft.vmf
    private final String packageName;

    // simple type name, i.e. name without package name, e.g. MyType
    private final String simpleTypeName;

    // indicates whether the rendered type shall be abstract
//    private final boolean abstr4ct;

    private Type(Model model, Class<?> clazz) {
        this.model = model;
        this.clazz = clazz;

        this.packageName = clazz.getPackage().getName();

        this.fullTypeName = clazz.getName();
        this.simpleTypeName = clazz.getSimpleName();

        computeProperties(model,this,clazz);
        computeImports(clazz, properties);
        computeExtends(model,this,clazz);
    }

    private static Collection<Property> computeProperties(
            Model model, Type type, Class<?> clazz) {
        List<Method> list = new ArrayList<Method>();
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().startsWith("get")) {
                list.add(m);
            }
        }
        Collection<Property> props = new ArrayList<Property>();
        for (Method m : list) {
            //if (Contained.class.getName().equals(m.getDeclaringClass().getName()))
            //	continue;
            Property f = new Property();
            f.init(type, m);
            if (model.isModelType(f.getFQN())) {
                f.setModelType(true);
            }
            props.add(f);
        }
        return props;
    }

    private static Collection<String> computeImports(
            Class<?> clazz,
            Collection<Property> properties) {
        Set<String> imps = new HashSet<>();
        for (Property m : properties) {
            if (!"java.lang".equals(m.getPackageName())) {
                if (!clazz.getPackage().getName().equals(m.getPackageName())) {
                    imps.add(m.getImport());
                }
            }
        }
        return imps;
    }

    private static void computeExtends(Model model, Type type, Class<?> clazz) {
        ArrayList<Type> ext3nds = new ArrayList<Type>();
        for (Class<?> ifs : clazz.getInterfaces()) {
            //if (Contained.class.getName().equals(ifs.getName()))
            //	continue;
            Type parent = model.resolve(ifs);
            if (parent == null)
                throw new IllegalArgumentException(
                        "Interface "
                                + clazz.getName()
                                + " extends "
                                + ifs.getName()
                                + ", but this type is not part of the declared model types.");
//            properties.addAll(parent.getProperties());
//            imports.addAll(parent.getImports());
            ext3nds.add(parent);
        }
        type.ext3nds = ext3nds.toArray(new Type[ext3nds.size()]);
    }

    public static Type newInstance(Model model, Class<?> clazz) {
        return new Type(model, clazz);
    }

    static String propertyNameFromGetter(Method getterMethod) {
        String name = getterMethod.getName().substring("get".length());
        name = name.substring(0, 1).toLowerCase() + name.substring(1);
        return name;
    }

    public List<Property> getProperties() {
        return propertiesReadOnly;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getFullTypeName() {
        return fullTypeName;
    }

    public String getSimpleTypeName() {
        return simpleTypeName;
    }

    public Model getModel() {
        return model;
    }
}
