/*
 * Copyright 2017-2018 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2018 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
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

import eu.mihosoft.vmf.runtime.core.internal.VObjectInternal;
import eu.mihosoft.vmf.runtime.core.internal.VObjectInternalModifiable;
import vjavax.observer.Subscription;

import java.util.*;

@SuppressWarnings("deprecation")
public final class Property {

    private final VObjectInternal parent;
    private final int propertyId;
    private final String name;
    private Type type;
    private boolean staticOnly;

    private static Map<String, Class<?>> primitives = new HashMap<>();

    {
        primitives.put("boolean", boolean.class);
        primitives.put("short", short.class);
        primitives.put("int", int.class);
        primitives.put("long", long.class);
        primitives.put("float", float.class);
        primitives.put("double", double.class);
        primitives.put("char", char.class);
        primitives.put("byte", byte.class);
    }

    static boolean isPrimitiveType(String clsName) {
        return primitives.containsKey(clsName);
    }

    private Class<?> getClassObjectByName(String clsName) {
        Class<?> result = primitives.get(clsName);

        if (result == null) {
            try {
                result = parent.getClass().getClassLoader().loadClass(clsName);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
        }

        return result;
    }

    private Property(VObjectInternal parent, String name, boolean staticOnly) {
        this.parent = parent;
        this.name = name;
        this.staticOnly = staticOnly;
        this.propertyId = parent._vmf_getPropertyIdByName(name);

        boolean isModelType = parent._vmf_getPropertyTypes()[propertyId] != -1;
        boolean isListType = parent._vmf_getPropertyTypes()[propertyId] == -2;

        // if we are a list type we check whether this property id is listed as
        // being part of 'properties with model element types' array
        // -> if that's the case we define this property as being a model type
        if (isListType) {
            isModelType = false;
            for (int pId : parent._vmf_getIndicesOfPropertiesWithModelElementTypes()) {
                if (propertyId == pId) {
                    isModelType = true;
                    break;
                }
            }
        }

        String typeName = parent._vmf_getPropertyTypeNames()[propertyId];
        try {
            if (isListType) {
                this.type = Type.newInstance(isModelType, isListType, typeName,
                        parent.getClass().getClassLoader().loadClass("eu.mihosoft.vcollections.VList"));
            } else if (isPrimitiveType(typeName)) {
                this.type = Type.newInstance(isModelType, isListType, typeName, getClassObjectByName(typeName));
            } else {
                this.type = Type.newInstance(isModelType, isListType, typeName,
                        parent.getClass().getClassLoader().loadClass(typeName));
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

    }

    @Deprecated
    public static Property newInstance(VObjectInternal parent, String name, boolean staticOnly) {
        return new Property(parent, name, staticOnly);
    }

    /**
     * Indicates whether this property is set. A property is defined as being set if the current value differs from the
     * default value, i.e., {@code Objects.equals(property.get(),property.getDefault())}.
     * @return {@code true} if this property is set; {@code false} otherwise
     */
    public boolean isSet() {

        if (parent == null || staticOnly) {
            throw new RuntimeException("Cannot check if property is set without access to an instance.");
        }

        return parent._vmf_isSetById(propertyId);
    }

    /**
     * Sets this property to the specified object.
     * @param o object to set
     * @see #isSet()
     */
    public void set(Object o) {

        if (parent == null || staticOnly) {
            throw new RuntimeException("Cannot set property without access to an instance.");
        }

        if (parent instanceof VObjectInternalModifiable) {
            ((VObjectInternalModifiable) parent)._vmf_setPropertyValueById(propertyId, o);
        } else {
            throw new RuntimeException("Cannot modify unmodifiable object");
        }
    }

    /**
     * Unsets this property, i.e., resets it to the specified default values.
     * @see #isSet()
     */
    public void unset() {

        if (parent == null || staticOnly) {
            throw new RuntimeException("Cannot set property without access to an instance.");
        }

        if (parent instanceof VObjectInternalModifiable) {
            ((VObjectInternalModifiable) parent)._vmf_setPropertyValueById(propertyId, getDefault());
        } else {
            throw new RuntimeException("Cannot modify unmodifiable object");
        }
    }

    /**
     * Returns the value of this property.
     * @return value of this property
     */
    public Object get() {

        if (parent == null || staticOnly) {
            throw new RuntimeException("Cannot get property without access to an instance.");
        }

        return parent._vmf_getPropertyValueById(propertyId);
    }

    @Deprecated()
    public void setDefault(Object value) {

        if (parent == null || staticOnly) {
            throw new RuntimeException("Cannot set property without access to an instance.");
        }

        if (parent instanceof VObjectInternalModifiable) {
            ((VObjectInternalModifiable) parent)._vmf_setDefaultValueById(propertyId, value);
        } else {
            throw new RuntimeException("Cannot modify unmodifiable object");
        }
    }

    /**
     * Returns the default value of this property.
     * @return default value of this property
     * @see #unset()
     * @see #isSet()
     */
    public Object getDefault() {

        if (parent == null || staticOnly) {
            throw new RuntimeException("Cannot set property without access to an instance.");
        }

        return parent._vmf_getDefaultValueById(propertyId);
    }

    /**
     * Returns the type of this property.
     * @return type of this property
     */
    public Type getType() {
        return type;
    }

    /**
     * Returns the name of this property.
     * @return name of this property
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the list of annotations of this object.
     *
     * @return the list of annotations of this object
     */
    public List<Annotation> annotations() {
        return parent._vmf_getPropertyAnnotationsById(propertyId);
    }

    /**
     * Returns the annotation specified by key.
     *
     * @param key the key of the annotation to return
     * @return the annotation specified by key
     */
    public Optional<Annotation> annotationByKey(String key) {
        return annotations().stream().filter(a -> key.equals(a.getKey())).findFirst();
    }

    /**
     * Adds the specified change listener to this property. Listeners will be notified about changes regardless
     * of whether change recording is enabled. This allows to react to and/or undo specific
     * changes without the overhead of storing all previous events in a collection. The listener registers
     * with this property only.
     *
     * @param l the listener to add
     * @return a subscription which allows to unsubscribe the specified listener
     */
    public Subscription addChangeListener(ChangeListener l) {
        if (parent == null || staticOnly) {
            throw new RuntimeException("Cannot add change listeners without access to an instance.");
        }

        Subscription s = parent.vmf().changes().addListener((c) -> {
            if(Objects.equals(getName(),c.propertyName())) {
                l.onChange(c);
            }
        }, false);

        return s;
    }
}
