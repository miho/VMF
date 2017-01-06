package eu.mihosoft.vmf.core;

import java.util.*;

public class Model {

	private String packageName;
	private Map<String, ModelType> types = new HashMap<>();
	private Map<Class<?>, Class<?>> containments = new HashMap<Class<?>, Class<?>>();
    private Map<Class<?>, String> containmentNames = new HashMap<Class<?>, String>();
    private Class<?>[] interfaces;

	public void init(Class<?>... interfaces) {
		this.interfaces = interfaces;
		Set<String> packages = new HashSet<String>();
		for (Class<?> clzz : interfaces) {
			if (!clzz.isInterface())
				throw new IllegalArgumentException(
						"Model may only contain interfaces.");
			initType(clzz);
			packages.add(clzz.getPackage().getName());

		}
		if (packages.size() > 1)
			throw new IllegalArgumentException(
					"All interfaces must be from same package, but found "
							+ packages);
		packageName = packages.iterator().next();
		//
	}

	public String getPackage() {
		return packageName;
	}

	public ModelType initType(Class<?> clazz) {

        ModelType t = ModelType.newInstance(this,clazz);
		types.put(clazz.getName(), t);

		return t;
	}

	public Collection<ModelType> getTypes() {
		return Collections.unmodifiableCollection(types.values());
	}

	public ModelType resolve(Class<?> clazz) {
		if (types.containsKey(clazz.getName()))
			return types.get(clazz.getName());
		else {
			if (Arrays.stream(interfaces).anyMatch(ifc -> clazz.equals(ifc)))
				return initType(clazz);
			else
				return null;
		}
	}

	public ModelType resolve(String clazzName) {
		return types.get(clazzName);
	}


    public Prop resolveOpposite(String opposite) {
        // TODO
        return null;
    }

    public boolean isModelType(String type) {
        for (Class<?> clazz : interfaces) {
            if (clazz.getName().equals(type))
                return true;
            for (Class<?> ifc : clazz.getInterfaces()) {
                if (ifc.getName().equals(type))
                    return true;
            }
        }
        return false;
    }
}
