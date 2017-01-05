package eu.mihosoft.vmf.playground;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;

public class Property {
	String name;
	String nameCc;
	boolean required;
	String type;
	String containedType;
	String collectionType;
	String pkg;
	boolean modelType;
	boolean collection;
	Type parent;
	Type containerType;
	Type containedTypeDescr;
	boolean contained;

	public void init(Type parent, Method m) {
		this.parent = parent;
		name = Type.propertyName(m);
		nameCc = Type.propertyNameCC(m);
//		contained = m.getDeclaredAnnotation(Containment.class) != null;
		collection = Collection.class.isAssignableFrom(m.getReturnType());
		if (collection && contained) {
			ParameterizedType retType = (ParameterizedType) m
					.getGenericReturnType();
			Class<?> containedClzz = (Class<?>) (retType
					.getActualTypeArguments()[0]);
			containedType = containedClzz.getSimpleName();
		} else if (collection)
		{
			ParameterizedType retType = (ParameterizedType) m
					.getGenericReturnType();
			Class<?> containedClzz = (Class<?>) (retType
					.getActualTypeArguments()[0]);
			collectionType = containedClzz.getSimpleName();
		} else if (contained) {
			containedType = m.getReturnType().getSimpleName();
		}
		type = m.getReturnType().getSimpleName();

		if (m.getReturnType().getPackage() != null)
			pkg = m.getReturnType().getPackage().getName();
		else
			pkg = "java.lang";

		required = (m.getDeclaredAnnotation(Required.class) != null);

	}

	public String getContainedType() {
		return containedType;
	}

	public String getContainedTypeFQN() {
		return parent.packageName + "." + containedType;
	}

	public Type getContainedTypeDescr() {
		return parent.model.resolve(getContainedTypeFQN());
	}

	public Type getParent() {
		return parent;
	}

	/**
	 * @return String for import statement.
	 */
	public String getImport() {
		return pkg + "." + type;
	}

	/**
	 * @return fully qualified type descriptor
	 */
	public String getFQN() {
		return pkg + "." + getDeclaration();
	}

	/**
	 * @return type declaration, assuming the type has been imported
	 */
	public String getDeclaration() {
		return type + (collection ? "<" + (contained?containedType:collectionType) + ">" : "");
	}

	public String getName() {
		return name;
	}

	public String getNameCc() {
		return nameCc;
	}

	public boolean isRequired() {
		return required;
	}

	public String getType() {
		return type;
	}

	public String getPkg() {
		return pkg;
	}

	public boolean isModelType() {
		return modelType;
	}

	public boolean isCollection() {
		return collection;
	}

	public void setModelType(boolean value) {
		this.modelType = value;
	}

	public void setContainerType(Type type) {
		this.containerType = type;
	}

	public Type getContainerType() {
		return containerType;
	}

	public boolean hasContainer() {
		return this.containerType != null;
	}

	public boolean isContained() {
		return contained;
	}
	
	public String getCollectionType()
	{
		return collectionType;
	}
}