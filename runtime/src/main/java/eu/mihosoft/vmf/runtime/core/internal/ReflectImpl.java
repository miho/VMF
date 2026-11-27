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
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmf.runtime.core.Reflect;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.Property;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * Created by miho on 18.06.2018.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
public class ReflectImpl implements Reflect {
    private static final ClassValue<Optional<Method>> GENERATED_DECLARATION_FACTORY = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> modelClass) {
            try {
                Class<?> builder = Class.forName(modelClass.getName() + "$Builder", false, modelClass.getClassLoader());
                return Optional.of(builder.getDeclaredMethod("__vmf_snapshotMetadata"));
            } catch (ClassNotFoundException | NoSuchMethodException olderModel) {
                return Optional.empty();
            }
        }
    };

    private VObject model;
    private List<Property> properties;
    private List<Property> readOnlyProperties;
    private boolean propertiesExposed;
    private boolean sortedUniqueProperties;
    private List<Annotation> annotations;
    private boolean staticOnly;
    private Class<?> modelAPIClass;

    public void setModel(VObject model) {
        this.model = model;
    }

    public void setModelAPIClass(Class<?> modelAPIClass) {
        this.modelAPIClass = modelAPIClass;
    }

    public void setStaticOnly(boolean staticOnly) {
        this.staticOnly = staticOnly;
    }

    @Override
    public List<Annotation> annotations() {
        if (annotations == null) {
            VObjectInternal parent = (VObjectInternal) model;

            annotations = new ArrayList<>(parent._vmf_getAnnotations());
        }

        return annotations;
    }

    @Override
    public List<Property> properties() {
        propertiesExposed = true;
        return propertyList();
    }

    private List<Property> propertyList() {
        if (properties == null) {
            VObjectInternal parent = (VObjectInternal) model;
            String[] names = parent._vmf_getPropertyNames();
            properties = new ArrayList<>(names.length);
            boolean sorted = true;
            String previous = null;
            for (String name : names) {
                properties.add(Property.newInstance(parent, name, staticOnly));
                if (previous != null && previous.compareTo(name) >= 0) sorted = false;
                previous = name;
            }
            sortedUniqueProperties = sorted;
        }
        return properties;
    }

    /** Reads declarations through the generated builder without running model construction. */
    public static Optional<ReflectImpl> generatedDeclaration(Class<?> modelClass) {
        Optional<Method> factory = GENERATED_DECLARATION_FACTORY.get(modelClass);
        if (factory.isEmpty()) return Optional.empty();
        try {
            return Optional.of((ReflectImpl) factory.get().invoke(null));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalArgumentException("Cannot read generated snapshot metadata of " + modelClass.getName(), failure);
        }
    }

    /** Reads the original supertype table, with the prototype path for older generated models. */
    public static String[] readDeclaredSuperTypeNames(Class<?> modelClass, Supplier<VObject> legacyPrototype) {
        VObjectInternal owner = (VObjectInternal) generatedDeclaration(modelClass)
                .map(declaration -> declaration.model).orElseGet(legacyPrototype);
        return owner instanceof TypeMetadataProvider
                ? ((TypeMetadataProvider) owner)._vmf_getGenericSuperTypeNames()
                : owner._vmf_getSuperTypeNames();
    }

    /** Visits the real property order without exposing a new writable reference. */
    public static List<Property> readProperties(Reflect reflect) {
        if (reflect.getClass() != ReflectImpl.class) return reflect.properties();
        ReflectImpl internal = (ReflectImpl) reflect;
        if (internal.readOnlyProperties == null) {
            internal.readOnlyProperties = Collections.unmodifiableList(internal.propertyList());
        }
        return internal.readOnlyProperties;
    }

    /** Uses the matching position of an unexposed, unique property layout, if available. */
    public static Property propertyAtOrNamed(Reflect reflect, int position, String name) {
        if (reflect.getClass() == ReflectImpl.class) {
            ReflectImpl internal = (ReflectImpl) reflect;
            if (!internal.propertiesExposed) {
                var list = internal.propertyList();
                if (internal.sortedUniqueProperties && position >= 0 && position < list.size()) {
                    Property property = list.get(position);
                    if (name.equals(property.getName())) return property;
                }
            }
        }
        // A caller may reorder or replace the public list, including from a binding callback.
        return reflect.propertyByName(name).orElseThrow();
    }

    @Override
    public Type type() {
        VObjectInternal vObj = (VObjectInternal) model;
        return vObj._vmf_getType();
    }

    @Override
    public List<Type> allTypes() {
        // find method handle for getTypes method
        try {
            return (List<Type>) modelAPIClass.getMethod("getTypes").invoke(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
