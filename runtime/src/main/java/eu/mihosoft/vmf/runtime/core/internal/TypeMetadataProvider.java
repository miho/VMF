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

import eu.mihosoft.vmf.runtime.core.TypeDescriptor;

/** Supplemental type information supplied by generated model implementations. */
@SuppressWarnings("deprecation")
public interface TypeMetadataProvider extends VObjectInternal {
    /** Returns the direct supertype declarations, including any concrete arguments. */
    default String[] _vmf_getGenericSuperTypeNames() {
        return _vmf_getSuperTypeNames();
    }

    /** Supplies the compatibility defaults of models generated before multiplicity metadata. */
    static eu.mihosoft.vmf.runtime.core.Multiplicity legacyMultiplicity(VObjectInternal owner, int id) {
        int kind = owner._vmf_getPropertyTypes()[id];
        boolean list = kind == -2;
        boolean many = list || kind == VMF_TYPE_MAP || kind == VMF_TYPE_SET;
        String type = owner._vmf_getPropertyTypeNames()[id];
        boolean primitive = java.util.Set.of("byte", "short", "int", "long", "float", "double", "boolean", "char").contains(type);
        return eu.mihosoft.vmf.runtime.core.Multiplicity.of(primitive ? 1 : 0, many ? -1 : 1, list, !list);
    }
    default eu.mihosoft.vmf.runtime.core.Multiplicity _vmf_getMultiplicity(int propertyId) {
        return legacyMultiplicity(this, propertyId);
    }

    /** Returns the structured type of one property. */
    default TypeDescriptor _vmf_getPropertyTypeDescriptor(int propertyId) {
        return TypeDescriptor.fromTypeName(_vmf_getPropertyTypeNames()[propertyId]);
    }
}
