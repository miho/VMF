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

        ModelType t = ModelType.newInstance(this, clazz);
		types.put(clazz.getName(), t);

		return t;
	}

	public Collection<ModelType> getTypes() {
		return Collections.unmodifiableCollection(types.values());
	}

	public Optional<ModelType> resolveType(Class<?> clazz) {
		if (types.containsKey(clazz.getName()))
			return Optional.of(types.get(clazz.getName()));
		else {
			if (Arrays.stream(interfaces).anyMatch(ifc -> clazz.equals(ifc)))
				return Optional.of(initType(clazz));
			else
				return Optional.empty();
		}
	}

	public Optional<ModelType> resolveType(String clazzName) {
		return Optional.ofNullable(types.get(clazzName));
	}

	/**
	 * Resolves the specified opposite property of the given model type.
	 * @param type model type
	 * @param oppositeProp fully qualified name of the opposite property of the given type, e.g.,
	 *                     '<em>eu.mihosoft.tutorial.MyType.myProp</em>'
	 * @return resolved opposite property or {@code Optional<Prop>.empty()} if the specified property does not exist
	 */
    public Optional<Prop> resolveOppositeOf(ModelType type, String oppositeProp) {

		Optional<Prop> prop = type.resolveProp(oppositeProp);

		if(prop.isPresent()) {
			return prop;
		}

		String[] typeNameParts = oppositeProp.split("\\.");

		// we tried to resolveType with this type already
		if(typeNameParts.length<=1) {
			return Optional.empty();
		}

		String typeName = "";

		for(int i = 0; i < typeNameParts.length -1; i++) {
			typeName+=typeNameParts[i];
		}

		String propName = typeNameParts[typeNameParts.length-1];

		Optional<ModelType> other = resolveType(typeName);

		if(other.isPresent()) {
			return other.get().resolveProp(propName);
		}

		return Optional.empty();
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
