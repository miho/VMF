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

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.PropertyChange;
import eu.mihosoft.vmf.runtime.core.VObject;

import vjavax.observer.collection.CollectionChangeEvent;
import java.util.Optional;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * Created by miho on 21.02.2017.
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
class ListChangeImpl implements Change {
    private final VObject object;
    private final String propertyName;
    private final CollectionChangeEvent<Object,VList<Object>, VListChange<Object>> evt;
    private final VList list;
    private final long timestamp;

    @SuppressWarnings({"deprecation", "unchecked"})
    public ListChangeImpl(VObject object, String propertyName, CollectionChangeEvent<Object,VList<Object>, VListChange<Object>> evt) {
        this.object = object;
        this.propertyName = propertyName;
        this.evt = evt;

        VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
        int propId = internal._vmf_getPropertyIdByName(propertyName);
        list = (VList) internal._vmf_getPropertyValueById(propId);

        this.timestamp = System.nanoTime();
    }

    @Override
    public VObject object() {
        return object;
    }

    @Override
    public String propertyName() {
        return propertyName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void apply(VObject obj) {

        VObjectInternalModifiable internal = (VObjectInternalModifiable) object;
        int propId = internal._vmf_getPropertyIdByName(propertyName);
        VList list = (VList) internal._vmf_getPropertyValueById(propId);
        if(evt.wasSet()) {
            if (evt.added().indices().length == list.size()) {
                list.setAll(evt.added().indices()[0], evt.added().elements());
            }
        } else if (evt.wasAdded()) {
            list.addAll(evt.added().indices(), evt.added().elements());
        } else if(evt.wasRemoved()) {
            list.removeAll(evt.removed().indices());
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void undo() {

        if (!isUndoable()) return;

        if (evt.wasSet()) {
            if (evt.removed().indices().length == list.size()) {
                list.setAll(evt.removed().indices()[0], evt.removed().elements());
            }
        } else if (evt.wasAdded()) {
            list.removeAll(evt.added().indices());
        } else if(evt.wasRemoved()) {
            list.addAll(evt.removed().indices(),evt.removed().elements());
        }

    }

    @Override
    public boolean isUndoable() {
        if (evt.wasSet()) {
            for(int index : evt.removed().indices()) {
                if(index > evt.removed().indices().length) {
                    return false;
                }
            }
        } else if (evt.wasAdded()) {
            // TODO check size changes
            for(int index : evt.added().indices()) {
                if (index > evt.added().indices().length) {
                    return false;
                }
            }
        } else if(evt.wasRemoved()) {
            // TODO check size changes
            for(int index : evt.removed().indices()) {
                if(index > evt.removed().indices().length) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public Optional<PropertyChange> propertyChange() {
        return Optional.empty();
    }

    @Override
    public Optional<VListChangeEvent<Object>> listChange() {
        VListChangeEvent<Object> result;
        if (evt.wasSet()) {
            result =  VListChangeEvent.getSetEvent(evt.source(), evt.added().indices(), evt.removed().elements(), evt.added().elements());
        } else if(evt.wasAdded()) {
            result =   VListChangeEvent.getAddedEvent(evt.source(), evt.added().indices(), evt.added().elements());
        } else if (evt.wasRemoved()) {
            result =   VListChangeEvent.getRemovedEvent(evt.source(), evt.removed().indices(), evt.removed().elements());
        } else {
            result = null;
        }

        return Optional.ofNullable(result);
    }

    @Override
    public ChangeType getType() {
        return ChangeType.LIST;
    }

    @Override
    public long getTimestamp() {
        return timestamp;
    }
}
