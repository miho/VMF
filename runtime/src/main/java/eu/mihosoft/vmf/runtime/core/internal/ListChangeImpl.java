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
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.Change;
import eu.mihosoft.vmf.runtime.core.PropertyChange;
import eu.mihosoft.vmf.runtime.core.VObject;

import vjavax.observer.collection.CollectionChangeEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
class ListChangeImpl implements ChangeInternal {
    private final VObject object;
    private final String propertyName;
    private final CollectionChangeEvent<Object,VList<Object>, VListChange<Object>> evt;
    private final VList list;
    private final long timestamp;
    private final String internalChangeInfo;
    private final int removalSize;

    
    public ListChangeImpl(VObject object, String propertyName, CollectionChangeEvent<Object,VList<Object>, VListChange<Object>> evt) {
        this(object, propertyName, evt, evt.eventInfo());
    }

    public ListChangeImpl(VObject object, String propertyName, CollectionChangeEvent<Object,VList<Object>, VListChange<Object>> evt, String internalChangeInfo) {
        this(object, propertyName, evt, System.nanoTime(), internalChangeInfo);
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    public ListChangeImpl(VObject object, String propertyName, CollectionChangeEvent<Object,VList<Object>, VListChange<Object>> evt, long timestamp, String internalChangeInfo) {
        this.object = object;
        this.propertyName = propertyName;
        this.evt = evt;

        VObjectInternal internal = (VObjectInternal) object;
        int propId = internal._vmf_getPropertyIdByName(propertyName);
        list = (VList) internal._vmf_getPropertyValueById(propId);

        this.timestamp = timestamp;
        this.internalChangeInfo = internalChangeInfo;
        this.removalSize = evt.wasRemoved() && !evt.wasSet() ? list.size() : -1;
    }
    
    @Override
    public String getInternalChangeInfo() {
        return internalChangeInfo;
    }

    boolean removesFrom(VList<?> source, VObject member) {
        if (list != source || evt.wasAdded()) return false;
        for (Object removed : evt.removed().elements()) {
            if (removed == member) return true;
        }
        return false;
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
        if (obj != object && (evt.added().elements().stream().anyMatch(VObject.class::isInstance)
                || evt.removed().elements().stream().anyMatch(VObject.class::isInstance))) {
            throw new IllegalArgumentException("Model-valued changes require their original object. "
                    + "Use JournalRecorder and JournalReplayer to replay onto another graph.");
        }

        VObjectInternal internal = (VObjectInternal) obj;
        int propId = internal._vmf_getPropertyIdByName(propertyName);
        VList list = (VList) internal._vmf_getPropertyValueById(propId);
        if(evt.wasSet()) {
            applySet(obj, list, evt.added());
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
            applySet(object, list, evt.removed());
        } else if (evt.wasAdded()) {
            list.removeAll(evt.added().indices());
        } else if(evt.wasRemoved()) {
            int[] indices = evt.removed().indices();
            List<Object> elements = evt.removed().elements();
            if (ManagedValues.isOrderedSet(list) || ReferenceCollections.isReferenceList(list)) {
                list.addAll(indices, elements);
                return;
            }
            int start = 0;
            while (start < indices.length) {
                int end = start + 1;
                while (end < indices.length && indices[end] == indices[end - 1] + 1) end++;
                list.addAll(indices[start], elements.subList(start, end));
                start = end;
            }
        }

    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void applySet(VObject target, VList list, VListChange<Object> change) {
        int[] indices = change.indices();
        if (indices.length == 0) return;
        // An unobserved containment sees the same generated callback for a
        // singleton set. Keep the original event payload for public listeners.
        if (indices.length == 1 && list instanceof ContainmentList<?>
                && ((ContainmentList<?>) list).hasNoListObservers()) {
            list.set(indices[0], change.elements().get(0));
            return;
        }
        boolean contiguous = true;
        for (int i = 1; i < indices.length; i++) {
            if (indices[i] != indices[0] + i) { contiguous = false; break; }
        }
        if (contiguous) {
            list.setAll(indices[0], change.elements());
        } else {
            var property = target.vmf().reflect().propertyByName(propertyName).orElseThrow();
            if (property.getMultiplicity().isUnique() || property.getKeyPropertyName().isPresent()) {
                // Apply the permutation as one edit, preserving untouched members between the changed positions.
                int first = Arrays.stream(indices).min().getAsInt();
                int last = Arrays.stream(indices).max().getAsInt();
                List<Object> range = new ArrayList<>(list.subList(first, last + 1));
                for (int i = 0; i < indices.length; i++) range.set(indices[i] - first, change.elements().get(i));
                list.setAll(first, range);
                return;
            }
            for (int i = 0; i < indices.length; i++) list.set(indices[i], change.elements().get(i));
        }
    }

    @Override
    public boolean isUndoable() {
        if (evt.wasSet() && (!Arrays.equals(evt.added().indices(), evt.removed().indices())
                || evt.removed().indices().length != evt.removed().elements().size())) {
            return false;
        }

        VListChange<Object> change = evt.wasAdded() ? evt.added() : evt.removed();
        int[] indices = change.indices();
        List<Object> elements = change.elements();
        if (indices.length != elements.size()) return false;

        int size = list.size();
        if (!evt.wasAdded() && size != removalSize) return false;
        if (evt.wasRemoved() && !evt.wasSet() && ManagedValues.isOrderedSet(list)
                && !java.util.Collections.disjoint(list, elements)) return false;
        if (ReferenceCollections.isReferenceList(list) && evt.wasRemoved()) {
            // Restoring the old edges must not claim an identity now present outside the edited slots.
            var restored = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            restored.addAll(evt.removed().elements());
            for (int i = 0; i < size; i++) {
                if ((!evt.wasSet() || Arrays.binarySearch(indices, i) < 0) && restored.contains(list.get(i))) return false;
            }
        }

        int previous = -1;
        for (int i = 0; i < indices.length; i++) {
            int index = indices[i];
            if (index <= previous) return false;
            if (evt.wasAdded()) {
                if (index >= size || !sameItem(elements.get(i), list.get(index))) return false;
            } else if (index - i > size) {
                return false;
            }
            previous = index;
        }
        return true;
    }

    private static boolean sameItem(Object expected, Object actual) {
        return expected instanceof VObject || actual instanceof VObject
                ? expected == actual : Objects.equals(expected, actual);
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
