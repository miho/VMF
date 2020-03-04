/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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

import eu.mihosoft.vmf.runtime.core.Changes;
import eu.mihosoft.vmf.runtime.core.VIterator;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Objects;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * Created by miho on 20.02.17.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
public interface VObjectInternalModifiable extends VObjectInternal {

    /**
     * Sets values of properties by id (calls setter methods).
     *
     * @param propertyId id of the property that shall be changed
     * @param value the value to set
     */
    void _vmf_setPropertyValueById(int propertyId, Object value);

    /**
     * Unsets the specified property.
     *
     * @param propertyId id of the property to unset
     */
    void _vmf_unsetById(int propertyId);

    default void setModelToChanges(Changes c) {
        ChangesImpl cImpl = (ChangesImpl) c;
        cImpl.setModel(this);
    }

    /**
     * Sets current unnamed container (named containers are represented via the specified properties instead).
     * @param container container to set
     */
    default void _vmf_setContainer(VObject container) {throw new RuntimeException("This type is not contained and this method shouldn't be called!");};

    /**
     * Unregisters 'this' from its current container.
    */
    default void _vmf_unregisterFromContainers() {}


}
