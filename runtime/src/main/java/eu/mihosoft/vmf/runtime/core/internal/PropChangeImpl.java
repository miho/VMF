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

import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.PropertyChange;
import eu.mihosoft.vmf.runtime.core.VObject;

import java.util.Objects;
import java.util.Optional;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * Created by miho on 21.02.2017.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
class PropChangeImpl implements ChangeInternal, PropertyChange {

    private final VObject object;
    private final String propertyName;
    private final Object oldValue;
    private final Object newValue;
    private final long timestamp;
    private final String internalChangeInfo;

    PropChangeImpl(VObject object, String propertyName, Object oldValue, Object newValue) {
        this.object = object;
        this.propertyName = propertyName;
        this.oldValue = oldValue;
        this.newValue = newValue;

        this.timestamp = System.nanoTime();

        this.internalChangeInfo = "";
    }

    PropChangeImpl(VObject object, String propertyName, Object oldValue, Object newValue, String internalChangeInfo) {
        this.object = object;
        this.propertyName = propertyName;
        this.oldValue = oldValue;
        this.newValue = newValue;

        this.timestamp = System.nanoTime();

        this.internalChangeInfo = internalChangeInfo;
    }

    public PropChangeImpl(VObject object, String propertyName, Object oldValue, Object newValue, long timestamp, String internalChangeInfo) {
        this.object = object;
        this.propertyName = propertyName;
        this.oldValue = oldValue;
        this.newValue = newValue;

        this.timestamp = timestamp;

        this.internalChangeInfo = internalChangeInfo;
    }

    @Override
    public String getInternalChangeInfo() {
        return internalChangeInfo;
    }

    public VObject object() {
        return object;
    }

    public String propertyName() {
        return propertyName;
    }

    public Object oldValue() {
        return oldValue;
    }

    public Object newValue() {
        return newValue;
    }

    @Override
    @SuppressWarnings({"deprecation", "unchecked"})
    public void apply(VObject obj) {
        VObjectInternalModifiable internal = (VObjectInternalModifiable) obj;
        int propId = internal._vmf_getPropertyIdByName(propertyName);

        internal._vmf_setPropertyValueById(propId, newValue);
    }

    @SuppressWarnings("deprecation")
    public void undo() {
        if (!isUndoable()) return;

        VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
        int propId = internal._vmf_getPropertyIdByName(propertyName);

        internal._vmf_setPropertyValueById(propId, oldValue);
    }

    @Override
    @SuppressWarnings({"deprecation", "unchecked"})
    public boolean isUndoable() {

        VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
        int propId = internal._vmf_getPropertyIdByName(propertyName);

        return Objects.equals(newValue, internal._vmf_getPropertyValueById(propId));
    }

    @Override
    public Optional<PropertyChange> propertyChange() {
        return Optional.of(this);
    }

    @Override
    public Optional<VListChangeEvent<Object>> listChange() {
        return Optional.empty();
    }

    @Override
    public ChangeType getType() {
        return ChangeType.PROPERTY;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }
}
