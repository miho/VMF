/*
 * Copyright 2017-2023 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2023 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final String name;

    private final Class<?> modelClass;

    private List<Type> superTypes;
    private VObject prototype;

    private Type(boolean modelType, boolean listType, String name, Class<?> modelClass) {
        this.modelType = modelType;
        this.listType = listType;
        this.name = name;
        this.modelClass = modelClass;
    }

    @Deprecated
    public static Type newInstance(boolean modelType, boolean listType, String name, Class<?> modelClass) {
        return new Type(modelType, listType, name, modelClass);
    }

    /**
     * Returns the name of this type.
     * @return name of this type (full type name, including package name)
     */
    public String getName() {
        return this.name;
    }

    /**
     * Returns the element type name if this type denotes a list type.
     * @return the element type name if this type denotes a list type; returns
     * an empty optional otherwise
     */
    public Optional<String> getElementTypeName() {

        if(!isListType()) {
            return Optional.empty();
        }

        String tName = getName();

        int firstIdx = tName.indexOf('<');

        // - '<' can't be at index 0 because the generic type name must be at least
        //   one char
        // - values smaller than zero indicate no occurence of '<' has been found
        if(firstIdx < 1) {
            return Optional.empty();
        }

        int lastIdx = tName.lastIndexOf('>');

        // - '>' can't be smaller than the occurrence of '<' since that would indicate
        //   an invalid type name
        if(lastIdx <= firstIdx) {
            return Optional.empty();
        }

        // finally, we can extract the element type name
        return Optional.of(tName.substring(firstIdx+1, lastIdx));
    }

    /**
     * Indicates whether this type is a model type.
     * @return {@code true} if this is a model type
     */
    public boolean isModelType() {
        return this.modelType;
    }

    /**
     * Indicates whether this type is a list type.
     * @return {@code true} if this is a list type
     */
    public boolean isListType() {
        return listType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Type type = (Type) o;
        return modelType == type.modelType && listType == type.listType &&
                Objects.equals(name, type.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(modelType, listType, name);
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
        ReflectImpl reflect = (ReflectImpl) getPrototype().vmf().reflect();
        reflect.setStaticOnly(true);
        return reflect;
    }

    private VObject getPrototype() {

        if(!isModelType()) {
            throw new RuntimeException(
                    "Cannot initialize reflection API of type type '"
                            + modelClass.getTypeName()
                            + "'. Reflection failed."
            );
        }

        if(prototype == null) {

            try {
                Method method = modelClass.getMethod("newInstance");
                prototype = (VObject) method.invoke(null);

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

        if(superTypes==null) {
            superTypes = new ArrayList<>();
            if(isModelType() && !isListType()) {

                eu.mihosoft.vmf.runtime.core.internal.VObjectInternal parent
                        = (eu.mihosoft.vmf.runtime.core.internal.VObjectInternal) getPrototype();
                String[] superTypeNames = parent._vmf_getSuperTypeNames();

                for (String tName : superTypeNames) {

                    try {
                        superTypes.add(Type.newInstance(true, false, tName, Class.forName(tName)));
                    } catch (ClassNotFoundException e) {
                        e.printStackTrace();
                        throw new RuntimeException(
                                "Cannot load super type class of type '"
                                        + tName
                                        + "'. Reflection failed."
                        );
                    }
                }
            }
        }

        return superTypes;
    }
}
