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

@SuppressWarnings("deprecation")
public final class Property {

    private VObjectInternal parent;
    private int propertyId;
    private String name;
    private Type type;

    private Property(VObjectInternal parent, String name) {
        this.parent = parent;
        this.name = name;
        this.propertyId = parent._vmf_getPropertyIdByName(name);

        boolean isModelType = parent._vmf_getPropertyTypes()[propertyId]!=-1;

        this.type = Type.newInstance(isModelType, parent._vmf_getPropertyTypeNames()[propertyId]);
    }

    public static Property newInstance(VObjectInternal parent, String name) {
        return new Property(parent, name);
    }

    public boolean isSet() {
        return parent._vmf_isSetById(propertyId);
    }

    public void set(Object o) {
        if(parent instanceof VObjectInternalModifiable) {
            ((VObjectInternalModifiable)parent)._vmf_setPropertyValueById(propertyId, o);
        } else {
            throw new RuntimeException("Cannot modify unmodifiable object");
        }
    }

    public void unset() {
        if(parent instanceof VObjectInternalModifiable) {
            ((VObjectInternalModifiable)parent)._vmf_setPropertyValueById(propertyId, getDefault());
        } else {
            throw new RuntimeException("Cannot modify unmodifiable object");
        }
    }

    public Object get() {
        return parent._vmf_getPropertyValueById(propertyId);
    }

    @Deprecated()
    public void setDefault(Object value) {
        if(parent instanceof VObjectInternalModifiable) {
            ((VObjectInternalModifiable)parent)._vmf_setDefaultValueById(propertyId, value);
        } else {
            throw new RuntimeException("Cannot modify unmodifiable object");
        }
    }

    public Object getDefault() {
        return parent._vmf_getDefaultValueById(propertyId);
    }

    public Type getType() {
        return type;
    }

    public String getName() {
        return name;
    }


}
