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
/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vmf.runtime.core.Annotation;
import eu.mihosoft.vmf.runtime.core.ObservableObject;
import eu.mihosoft.vmf.runtime.core.Type;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal interface. Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
public interface VObjectInternal extends VObject, ObservableObject {

    /**
     * Returns the type id of this class.
     */
    int _vmf_getTypeId();

    /**
     * Returns the type of this object.
     * @return type of this object
     */
    Type _vmf_getType();

    /**
     * Returns the names of the super types of this class.
     * @return the names of the super types of this class
     */
    String[] _vmf_getSuperTypeNames();

    /**
     * Returns the names of the properties defined in this type/object.
     *
     */
    public String[] _vmf_getPropertyNames();

    /**
     * Returns the type ids of the properties defined in this type/object.
     */
    public int[] _vmf_getPropertyTypes();

    /**
     * Returns the type names of the properties defined in this type/object.
     */
    public String[] _vmf_getPropertyTypeNames();


    /**
     * Returns values of properties by id (calls getter methods).
     */
    public Object _vmf_getPropertyValueById(int propertyId);

    /**
     * Returns the indices of all properties with model types or collections
     * that contain model instances.
     */
    public int[] _vmf_getIndicesOfPropertiesWithModelTypes();

    /**
     * Returns the indices of all collection properties with model element
     * types.
     */
    public int[] _vmf_getIndicesOfPropertiesWithModelElementTypes();

    /**
     * Returns the indices of all properties with model instances
     * or collections that contain model instances.
     */
    public int[] _vmf_getIndicesOfPropertiesWithModelTypeOrElementTypes();

    /**
     * Returns the indices of all children properties which declare this object as
     * parent container (opposite). This includes collection properties that
     * contain children.
     */
    public int[] _vmf_getChildrenIndices();

    /**
     * Returns the indices of all parent properties which declare this object as
     * child/contained element (opposite).
     */
    public int[] _vmf_getParentIndices();

    /**
     * Returns the property index of the specified property name.
     * @param propertyName
     * @return the property index of the specified property name or {@code -1}
     *         if the specified property does not exist
     */
    public int _vmf_getPropertyIdByName(String propertyName);

    /**
     * Indicates whether this object is a read-only instance.
     * @return {@code true} if this object is a read-only instance; {@code false} otherwise
     */
    default boolean _vmf_isReadOnly() {
        return false;
    }

    /**
     * Returns the mutable instance wrapped by this instance. If this object is a mutable
     * instance this method is a no-op.
     * @return the mutable instance wrapped by this instance
     */
    default VObject _vmf_getMutableObject() {
        return this;
    }


    /**
     * Returns the default value of the specified property.
     * @param propertyId id of the property
     * @return the default value of the specified property
     */
    Object _vmf_getDefaultValueById(int propertyId);

    /**
     * Defines the default value for the specified property.
     * @param propertyId id of the property
     * @param defaultValue the default value to set
     */
    void _vmf_setDefaultValueById(int propertyId, Object defaultValue);

    /**
     * Determines whether the specified property is set.
     * @param propertyId id of the property
     * @return {@code true} if this property is set; {@code false} otherwise
     */
    boolean _vmf_isSetById(int propertyId);


    /**
     * @return objects that reference this object
     */
    VList<VObject> _vmf_referencedBy();

    /**
     * @return objects that are referenced by this object
     */
    VList<VObject> _vmf_references();

    /**
     * @return annotations of the property specified by property id
     */
    List<Annotation> _vmf_getPropertyAnnotationsById(int propertyId);

    /**
     * @return annotations of this object
     */
    List<Annotation> _vmf_getAnnotations();

    /**
     * @return current unnamed container (named containers are represented via the specified properties instead)
     */
    default VObject _vmf_getContainer() {throw new RuntimeException("This type is not contained and this method shouldn't be called!");};

    /**
     * 
     * @param threadlocalMap the thread local map for equality checks
     */
    void _vmf_setThreadLocalEquals(ThreadLocal<java.util.Map<EqualsPair, ?>> threadlocalMap);

    /**
     * @param sb string builder used to create the final string
     * @param identityMap the identity map for checking for recursion
     */
    void __vmf_toString(StringBuilder sb, java.util.IdentityHashMap<Object, ?> identityMap);

    /**
   * The purpose of this class is to store a pair of objects used for equals().
   * This class's equals() method checks equality by object identity. Same
   * for hashCode() which uses identity hashes of 'first' and 'second' to
   * compute the hash.
   *
   * This class can be used in conjunction with a regular HashMap to get
   * similar results to an IdentityHashMap, except that in this case identity
   * pairs can be used. And we don't have to use a map implementation that is
   * deliberately broken by design.
   */
   static class EqualsPair {

        final Object first;
        final Object second;

        public EqualsPair(Object first, Object second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(System.identityHashCode(first),
                    System.identityHashCode(second));
        }

        @Override
        public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final EqualsPair other = (EqualsPair) obj;
        if (this.first!=other.first) {
            return false;
        }
        if (this.second!=other.second) {
            return false;
        }
        return true;
        }
    }

    /**
     * Indicates whether two objects are equal (checks all properties).
     * @param o object to compare
     * @return {@code true} if objects are equal; {@code false} otherwise
     */
    boolean _vmf_equals(Object o);

    /**
     * TBD
     * @return
     */
    int _vmf_hashCode();
}
