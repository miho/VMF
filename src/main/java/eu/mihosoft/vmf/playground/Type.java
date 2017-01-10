package eu.mihosoft.vmf.playground;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;

public class Type {

	Class<?> clzz;
	Model model;
	Collection<String> imports;
	Collection<Property> properties;
	private Property[] required;
	String clzzName;
	String typeName;
	String packageName;
	String simpleName;
	boolean contained;
	Type[] ext3nds;
	boolean abstr4ct;

	public Collection<String> getImports() {
		return imports;
	}

	public Collection<Property> getProperties() {
		return properties;
	}

	public Property[] getRequired() {
		if (required != null)
			return required;
		else
			return required = properties.stream().filter(p -> p.isRequired())
					.toArray(Property[]::new);
	}

	public int getRequiredCount() {
		return getRequired().length;
	}

	public String getTypeName() {
		return typeName;
	}

	public String getClzzName() {
		return clzzName;
	}

	public String getPackageName() {
		return packageName;
	}

	public String getClzzPackageName() {
		return packageName + ".impl";
	}

	public String getJavaFQN() {
		return getClzzPackageName() + "." + clzzName;
	}

	public boolean isAbstract() {
		return abstr4ct;
	}

	public boolean isContained() {
		return contained;
	}

	public void init(Class<?> clzz, Model model) {
		assert clzz.isInterface();
		this.clzz = clzz;
		this.model = model;
		// contained = Contains.class.isAssignableFrom(clzz);
		properties = initProperties(this, clzz, model);
		imports = initImports(clzz, properties);
		typeName = clzz.getName();
		simpleName = clzz.getSimpleName();
		clzzName = clzz.getSimpleName() + "Object";
		packageName = clzz.getPackage().getName();
		// abstr4ct = clzz.getDeclaredAnnotation(Abstract.class) != null;

		ArrayList<Type> ext3nds = new ArrayList<Type>();
		for (Class<?> ifs : clzz.getInterfaces()) {
			//if (Contains.class.getTypeName().equals(ifs.getTypeName()))
			//	continue;
			Type parent = model.resolve(ifs);
			if (parent == null)
				throw new IllegalArgumentException(
						"Interface "
								+ clzz.getName()
								+ " extends "
								+ ifs.getName()
								+ ", but this type is not part of the declared model types.");
			properties.addAll(parent.getProperties());
			imports.addAll(parent.getImports());
			ext3nds.add(parent);
		}
		this.ext3nds = ext3nds.toArray(new Type[ext3nds.size()]);
		initContainments(clzz, properties);
	}

	private void initContainments(Class<?> clzz, Collection<Property> properties) {
		for (Method m : clzz.getDeclaredMethods()) {
			boolean containment = false;//m.getDeclaredAnnotation(Containment.class) != null;
			boolean collection = Collection.class.equals(m.getReturnType());
			if (containment && collection) {
				Property p = properties.stream()
						.filter(e -> m.getName().equals("get" + e.getName()))
						.findFirst().get();
				if (p == null)
					throw new IllegalArgumentException(
							"Error in @Contaiment definition for "
									+ m.getName() + " in interface "
									+ clzz.getName());
				Class<?> contained = (Class<?>) ((ParameterizedType) m
						.getGenericReturnType()).getActualTypeArguments()[0];

				model.addContainment(p.getName(), clzz, contained);
				p.setContainerType(this);
			} else if (containment) {
				Class<?> contained = m.getReturnType();
				Property p = properties.stream()
						.filter(e -> m.getName().equals("get" + e.getName()))
						.findFirst().get();
				if (p == null)
					throw new IllegalArgumentException(
							"Error in @Contaiment definition for "
									+ m.getName() + " in interface "
									+ clzz.getName());
				model.addContainment(p.getName(), clzz, contained);
				p.setContainerType(this);
			}
		}

	}

	static Collection<String> initImports(Class<?> clzz,
			Collection<Property> properties) {
		Set<String> imps = new HashSet<String>();
		for (Property m : properties) {
			if (!"java.lang".equals(m.getPkg())) {
				if (!clzz.getPackage().getName().equals(m.getPkg())) {
					imps.add(m.getImport());
				}
			}
		}
		return imps;
	}

	static Collection<Property> initProperties(Type type, Class<?> clzz,
													Model model) {
		List<Method> list = new ArrayList<Method>();
		for (Method m : clzz.getDeclaredMethods()) {
			if (m.getName().startsWith("get")) {
				list.add(m);
			}
		}
		Collection<Property> props = new ArrayList<Property>();
		for (Method m : list) {
			//if (Contains.class.getTypeName().equals(m.getDeclaringClass().getTypeName()))
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

	static String propertyName(Method m) {
		String name = m.getName().substring(3);
		return name;
	}

	static String propertyNameCC(Method m) {
		String name = propertyName(m);
		name = name.substring(0, 1).toLowerCase() + name.substring(1);
		return name;
	}

	public Type[] getExtended() {
		return ext3nds;
	}

	public String getSimpleName() {
		return simpleName;
	}

	public Type getContainer() {
		return model.getContainment(this.clzz);
	}

	public Object getContainmentName() {
		return model.getContainmentName(this.clzz);
	}
}
