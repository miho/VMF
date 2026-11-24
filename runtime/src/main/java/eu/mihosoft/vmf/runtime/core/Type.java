/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * If you use this software for scientific research then please cite the following publication(s):
 *
 * M. Hoffer, C. Poliwoda, & G. Wittum. (2013). Visual reflection library:
 * a framework for declarative GUI programming on the Java platform.
 * Computing and Visualization in Science, 2013, 16(4),
 * 181–192. http://doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core;

import eu.mihosoft.vmf.runtime.core.internal.ReflectImpl;

import java.lang.reflect.InvocationTargetException;
import eu.mihosoft.vmf.runtime.core.internal.TypeMetadataProvider;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.TypeVariable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Represents a type, e.g. {@code java.lang.Integer} or {@code your.pkg.ModelType}.
 * 
 * @see {@link Property}
 * @see {@link Reflect}
 * @see {@link Annotation}
 */
public final class Type {

    private final boolean modelType;
    private final boolean listType;
    private final boolean interfaceOnly;
    private final String name;
    private final TypeDescriptor descriptor;

    private final Class<?> modelClass;

    private List<Type> superTypes;
    private VObject prototype;

    private Type(boolean modelType, boolean listType, boolean interfaceOnly, String name, Class<?> modelClass, TypeDescriptor descriptor) {
        this.descriptor = descriptor;
        this.modelType = modelType;
        this.listType = listType;
        this.interfaceOnly = interfaceOnly;
        this.name = name;
        this.modelClass = modelClass;
    }

//    @Deprecated
//    public static Type newInstance(boolean modelType, boolean listType, String name, Class<?> modelClass) {
//        return new Type(modelType, listType, false, name, modelClass);
//    }

    @Deprecated
    public static Type newInstance(boolean modelType, boolean listType, boolean interfaceOnly, String name, Class<?> modelClass) {
        return new Type(modelType, listType, interfaceOnly, name, modelClass, TypeDescriptor.fromTypeName(name));
    }

    /**
     * Returns the name of this type.
     * @return name of this type (full type name, including package name)
     */
    public String getName() {
        return this.name;
    }

    Class<?> javaClass() {
        return modelClass;
    }

    /**
     * Returns the element type name if this type denotes a list type.
     * @return the element type name if this type denotes a list type; returns
     * an empty optional otherwise
     */
    public Optional<String> getElementTypeName() {

        if ((!listType && !isSetType()) || descriptor.getArguments().size() != 1) return Optional.empty();
        return Optional.of(descriptor.getArguments().get(0).toString());
    }

    /** Returns the structured declaration of this type. */
    public TypeDescriptor getDescriptor() { return descriptor; }

    /** Indicates whether the property is a managed VMF map. */
    public boolean isMapType() {
        return modelClass == eu.mihosoft.vcollections.VMap.class && descriptor.getArguments().size() == 2;
    }

    /** Indicates whether the property is a managed, unordered set. */
    public boolean isSetType() {
        return modelClass == eu.mihosoft.vcollections.VSet.class && descriptor.getArguments().size() == 1;
    }

    static Type propertyType(boolean model, boolean list, boolean interfaceOnly, String name,
                             Class<?> javaClass, TypeDescriptor descriptor) {
        return new Type(model, list, interfaceOnly, name, javaClass, descriptor);
    }

    /**
     * Indicates whether this type is a model type.
     * @return {@code true} if this is a model type
     */
    public boolean isModelType() {
        return this.modelType;
    }

    /**
     * Tests whether this model object type strictly inherits the given model type.
     * A declaration with no arguments matches all its applications. A parameterized
     * supertype must also match the arguments after inheritance substitution.
     * Declaration identity includes the defining Java class loader.
     *
     * @param supertype model declaration or application to look for
     * @return true for a proper model supertype, including indirect inheritance
     */
    public boolean isModelSubtypeOf(Type supertype) {
        Objects.requireNonNull(supertype, "supertype");
        if (!modelType || listType || !supertype.modelType || supertype.listType
                || modelClass == supertype.modelClass || !supertype.modelClass.isAssignableFrom(modelClass)) {
            return false;
        }
        if (supertype.descriptor.getArguments().isEmpty()) return true;
        for (Type parent : superTypes()) {
            if (parent.modelClass == supertype.modelClass && parent.descriptor.equals(supertype.descriptor)) return true;
            if (parent.isModelSubtypeOf(supertype)) return true;
        }
        return false;
    }

    /**
     * Indicates whether this type is a list type.
     * @return {@code true} if this is a list type
     */
    public boolean isListType() {
        return listType;
    }

    /**
     * Indicates whether this type is an interface-only type.
     * @return {@code true} if this is an interface-only type
     */
    public boolean isInterfaceOnly() {
        return interfaceOnly;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Type type = (Type) o;
        return modelType == type.modelType && listType == type.listType &&
                Objects.equals(name, type.name) && descriptor.equals(type.descriptor);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelType, listType, name, descriptor);
    }

    @Override
    public String toString() {
        return "[ name=" + name + ", modelType=" + modelType + ", listType=" + listType + ", cls: " + modelClass + " ]";
    }

    /**
     * Returns the reflection API of this type (static reflection only).
     * @return the reflection API of this type (static reflection only)
     */
    @SuppressWarnings("deprecation")
    public Reflect reflect() {

        if(interfaceOnly) {
            throw new RuntimeException(
                    "Cannot initialize reflection API of interface-only type '"
                            + modelClass.getTypeName()
                            + "'. Reflection failed."
            );
        }

        ReflectImpl reflect = (ReflectImpl) getPrototype().vmf().reflect();
        reflect.setStaticOnly(true);
        return reflect;
    }

    private VObject getPrototype() {

        if(interfaceOnly) {
            throw new RuntimeException(
                    "Cannot initialize prototype of interface-only type '"
                            + modelClass.getTypeName()
                            + "'. Instantiation failed."
            );
        }

        if(!isModelType()) {
            throw new RuntimeException(
                    "Cannot initialize reflection API of type type '"
                            + modelClass.getTypeName()
                            + "'. Reflection failed."
            );
        }

        if(prototype == null) {

            try {

                // call newBuilder().build() to create a new instance of the model type
                // we don't use the newInstance() method here because some types don't have it
                Method method = modelClass.getMethod("newBuilder");
                Builder builder = (Builder) method.invoke(null);
                prototype = builder.build();

            } catch (NoSuchMethodException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InvocationTargetException e) {
                e.printStackTrace();
            } catch (ClassCastException e) {
                e.printStackTrace();
            }
        }

        return prototype;
    }


    /**
     * Returns the super types of this type. Note, this method only returns the super types if the type represented by
     * this object is a model type.
     * @return list of super types of this type if this is a model type; returns an empty list otherwise
     * (for non-model types, such as {@code java.lang.String} and list types, regardless of the element type)
     */
    @SuppressWarnings("deprecation")
    public List<Type> superTypes() {

        if (superTypes == null) {
            List<Type> parents = new ArrayList<>();
            if (isModelType() && !isListType()) {
                if (interfaceOnly) {
                    // Interface-only types have no prototype. Their generated Java supertypes
                    // retain the declared argument expressions, including forwarded type variables.
                    for (java.lang.reflect.Type parent : modelClass.getGenericInterfaces()) {
                        Class<?> raw = parent instanceof ParameterizedType
                                ? (Class<?>) ((ParameterizedType) parent).getRawType() : (Class<?>) parent;
                        if (raw == VObject.class || !VObject.class.isAssignableFrom(raw)) continue;
                        parents.add(parentType(raw, resolveArgument(parent)));
                    }
                } else {
                    var prototype = (eu.mihosoft.vmf.runtime.core.internal.VObjectInternal) getPrototype();
                    String[] declarations = prototype instanceof TypeMetadataProvider
                            ? ((TypeMetadataProvider) prototype)._vmf_getGenericSuperTypeNames()
                            : prototype._vmf_getSuperTypeNames();
                    for (String declared : declarations) {
                        TypeDescriptor parent = TypeDescriptor.fromTypeName(declared);
                        try {
                            parents.add(parentType(Class.forName(parent.getName(), false, modelClass.getClassLoader()), parent));
                        } catch (ClassNotFoundException ex) {
                            throw new IllegalStateException("Cannot resolve model supertype " + declared, ex);
                        }
                    }
                }
            }
            superTypes = parents;
        }

        return superTypes;
    }

    private TypeDescriptor resolveArgument(java.lang.reflect.Type argument) {
        if (argument instanceof Class<?>) return TypeDescriptor.of(((Class<?>) argument).getName());
        if (argument instanceof ParameterizedType) {
            ParameterizedType application = (ParameterizedType) argument;
            TypeDescriptor[] arguments = java.util.Arrays.stream(application.getActualTypeArguments())
                    .map(this::resolveArgument).toArray(TypeDescriptor[]::new);
            return TypeDescriptor.of(((Class<?>) application.getRawType()).getName(), arguments);
        }
        if (argument instanceof TypeVariable<?>) {
            TypeVariable<?> variable = (TypeVariable<?>) argument;
            int index = java.util.Arrays.asList(modelClass.getTypeParameters()).indexOf(variable);
            if (index >= 0 && index < descriptor.getArguments().size()) return descriptor.getArguments().get(index);
            return TypeDescriptor.parameter(((Class<?>) variable.getGenericDeclaration()).getName(), variable.getName());
        }
        throw new IllegalStateException("Unsupported model supertype argument " + argument);
    }

    private static Type parentType(Class<?> raw, TypeDescriptor descriptor) {
        boolean interfaceOnly = !Mutable.class.isAssignableFrom(raw) && !Immutable.class.isAssignableFrom(raw);
        return new Type(true, false, interfaceOnly, descriptor.getName(), raw, descriptor);
    }
}
