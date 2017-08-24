/*
 * Copyright 2016-2017 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
package eu.mihosoft.vmf.core;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.List;

/**
 * Created by miho on 21.03.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
public class TypeUtil {
    private TypeUtil() {
        throw new AssertionError("Don't instantiate me!");
    }

    /**
     * Returns the specified type as string. Collections are specified as array.
     * Thus, arrays and collection types other than lists are currently not supported.
     *
     * @param model current model
     * @param cls   class object to convert
     * @return the specified type as string
     */
    public static String getTypeAsString(Model model, Class<?> cls) {
        String packageName;
        String typeName;

        if (cls.isPrimitive()) {
            typeName = cls.getSimpleName();
            packageName = "";

        } else if (Collection.class.isAssignableFrom(cls)) {
            throw new RuntimeException(
                    "Collections can only be specified with array syntax, i.e., '<Type>[]'");
        } else if (cls.isArray()) {
            Class<?> containedClazz = cls.getComponentType();
            typeName = "VList<" + ModelType.primitiveToBoxedType(
                    model.convertModelTypeToDestination(containedClazz)) + ">";

            packageName = "eu.mihosoft.vcollections";

        } else {
            typeName = cls.getSimpleName();
            packageName = model.
                    convertModelPackageToDestination(cls.getPackage().getName());
        }

        if (!packageName.isEmpty()) {
            return packageName + "." + typeName;
        } else {
            return typeName;
        }
    }

    /**
     * Returns the return type of the specified method as string. Collections are specified as array.
     * Thus, arrays and collection types other than lists are currently not supported.
     *
     * @param model current model
     * @param m     method object to convert
     * @return the specified type as string
     */
    public static String getReturnTypeAsString(Model model, Method m) {
        String typeName = "";
        String packageName = "";
        String genericTypeName = "";
        String genericPackageName = "";

        Class<?> propClass = m.getReturnType();

        if (propClass.isPrimitive()) {
            typeName = propClass.getSimpleName();
            packageName = "";

        } else if (Collection.class.isAssignableFrom(propClass)) {

            ParameterizedType retType = null;

            if (m.getGenericReturnType() != null) {
                if (m.getGenericReturnType() instanceof ParameterizedType) {
                    retType = (ParameterizedType) m
                            .getGenericReturnType();
                }
            }

            Class<?> containedClazz;

            if (retType != null) {
                containedClazz = (Class<?>) (retType
                        .getActualTypeArguments()[0]);
            } else {
                containedClazz = Object.class;
            }

            if (!List.class.isAssignableFrom(propClass)) {
                throw new IllegalArgumentException(
                        "Currently only 'java.util.List<?>' is supported as Collection type.");
            } else {
                if (containedClazz.getPackage() == null) {
                    genericPackageName = "";
                } else {
                    genericPackageName = model.
                            convertModelPackageToDestination(
                                    containedClazz.getPackage().getName());
                }

                genericTypeName = containedClazz.getSimpleName();
            }

            typeName = "VList<" + model.
                    convertModelTypeToDestination(containedClazz) + ">";
            packageName = "eu.mihosoft.vcollections";

        } else if (propClass.isArray()) {
            Class<?> containedClazz = propClass.getComponentType();
            typeName = "VList<" + ModelType.primitiveToBoxedType(
                    model.
                            convertModelTypeToDestination(containedClazz)) + ">";
            // System.out.println("TYPENAME: " + typeName);

            packageName = "eu.mihosoft.vcollections";

            if (containedClazz.getPackage() == null) {
                genericPackageName = "";
            } else {
                genericPackageName = model.
                        convertModelPackageToDestination(containedClazz.getPackage().getName());
            }

            genericTypeName = containedClazz.getSimpleName();
        } else if (m.getGenericReturnType() != null) {

            ParameterizedType retType = null;

            if (m.getGenericReturnType() != null) {
                if (m.getGenericReturnType() instanceof ParameterizedType) {
                    retType = (ParameterizedType) m
                            .getGenericReturnType();
                }
            }

            Class<?> containedClazz=null;

            if (retType != null) {
                containedClazz = (Class<?>) (retType
                        .getActualTypeArguments()[0]);
            }

            if(containedClazz==null) {
                typeName = propClass.getSimpleName();

                packageName = model.
                        convertModelPackageToDestination(propClass.getPackage().getName());
            } else {

                if (containedClazz.getPackage() == null) {
                    genericPackageName = "";
                } else {
                    genericPackageName = model.
                            convertModelPackageToDestination(
                                    containedClazz.getPackage().getName());
                }

                genericTypeName = containedClazz.getSimpleName();

                typeName = m.getReturnType().getSimpleName() + "<" + model.
                        convertModelTypeToDestination(containedClazz) + ">";
                packageName = model.convertModelPackageToDestination(m.getReturnType().getPackage().getName());
            }

        } else {
            typeName = propClass.getSimpleName();

            packageName = model.
                    convertModelPackageToDestination(propClass.getPackage().getName());
        }

        if (!packageName.isEmpty()) {
            return packageName + "." + typeName;
        } else {
            return typeName;
        }
    }
}
