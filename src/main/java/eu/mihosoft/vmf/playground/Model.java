package eu.mihosoft.vmf.playground;

import com.sun.javafx.UnmodifiableArrayList;

import java.util.*;

public class Model {

	String packageName;
	Map<String, Type> types = new HashMap<String, Type>();
	Map<Class<?>, Class<?>> containments = new HashMap<Class<?>, Class<?>>();
	Map<Class<?>, String> containmentNames = new HashMap<Class<?>, String>();
	Class<?>[] interfaces;

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

	public Type initType(Class<?> clzz) {
		Type t = new Type();
		types.put(clzz.getName(), t);
		t.init(clzz, this);
		return t;
	}

	public Collection<Type> getTypes() {
		return Collections.unmodifiableCollection(types.values());
	}

	public Type resolve(Class<?> clzz) {
		if (types.containsKey(clzz.getName()))
			return types.get(clzz.getName());
		else {
			if (Arrays.stream(interfaces).anyMatch(ifc -> clzz.equals(ifc)))
				return initType(clzz);
			else
				return null;
		}
	}

	public Type resolve(String clzzName) {
		return types.get(clzzName);
	}

	public void addContainment(String name, Class<?> container,
			Class<?> contained) {
		containments.put(contained, container);
		containmentNames.put(contained, name);
	}

	public boolean isModelType(String type) {
		for (Class<?> clzz : interfaces) {
			if (clzz.getName().equals(type))
				return true;
			for (Class<?> ifc : clzz.getInterfaces()) {
				if (ifc.getName().equals(type))
					return true;
			}
		}
		return false;
	}

	public Type getContainment(Class<?> clzz) {
		return resolve(containments.get(clzz));
	}

	public String getContainmentName(Class<?> clzz) {
		return containmentNames.get(clzz);
	}

}
