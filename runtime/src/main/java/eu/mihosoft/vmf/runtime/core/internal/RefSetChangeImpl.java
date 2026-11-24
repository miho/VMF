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

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vmf.runtime.core.*;
import vjavax.observer.collection.CollectionChangeEvent;

import java.util.*;
import java.util.stream.Collectors;

/** Retains native list undo for positions and exposes only reference-set semantics to callers. */
final class RefSetChangeImpl extends ListChangeImpl implements RefSetChange {
    private final RefSet<?> source;
    private final List<VObject> added;
    private final List<VObject> removed;
    private final List<Integer> indices;

    RefSetChangeImpl(VObject owner, String name, CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> event) {
        super(owner, name, event);
        VObjectInternal internal = (VObjectInternal) owner;
        source = (RefSet<?>) internal._vmf_getPublicPropertyValueById(internal._vmf_getPropertyIdByName(name));
        if (source instanceof OrderedRefSet) {
            added = members(event.added().elements());
            removed = members(event.removed().elements());
            int[] positions = event.wasAdded() ? event.added().indices() : event.removed().indices();
            indices = Arrays.stream(positions).boxed().collect(Collectors.toUnmodifiableList());
        } else {
            added = difference(event.added().elements(), event.removed().elements());
            removed = difference(event.removed().elements(), event.added().elements());
            indices = List.of();
        }
    }

    private static List<VObject> members(List<?> values) {
        return values.stream().map(VObject.class::cast).collect(Collectors.toUnmodifiableList());
    }

    private static List<VObject> difference(List<?> members, List<?> retained) {
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        identities.addAll(retained);
        return members.stream().filter(member -> !identities.contains(member)).map(VObject.class::cast)
                .collect(Collectors.toUnmodifiableList());
    }

    @Override public RefSet<?> source() { return source; }
    @Override public List<VObject> added() { return added; }
    @Override public List<VObject> removed() { return removed; }
    @Override public List<Integer> indices() { return indices; }
    @Override public ChangeType getType() { return ChangeType.REFERENCE_SET; }
    @Override public Optional<RefSetChange> referenceSetChange() { return Optional.of(this); }
    @Override public Optional<VListChangeEvent<Object>> listChange() { return Optional.empty(); }

    @Override public boolean isUndoable() {
        if (source.isReadOnly()) return false;
        if (source instanceof OrderedRefSet) return super.isUndoable();
        return matches(added, removed);
    }

    private boolean matches(List<VObject> present, List<VObject> absent) {
        return present.stream().allMatch(source::contains) && absent.stream().noneMatch(source::contains);
    }

    @Override public void undo() {
        if (source instanceof OrderedRefSet) {
            if (isUndoable()) super.undo();
        } else if (isUndoable()) {
            editMembership(added, removed);
        }
    }

    @Override public void apply(VObject target) {
        if (target != object()) throw new IllegalArgumentException("A native reference edit requires its original owner");
        if (source instanceof OrderedRefSet) {
            super.apply(target);
        } else {
            if (!matches(removed, added)) throw new IllegalArgumentException("Reference membership precondition differs");
            editMembership(removed, added);
        }
    }

    private void editMembership(List<VObject> remove, List<VObject> add) {
        Set<Object> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        removed.addAll(remove);
        List<Object> desired = new ArrayList<>();
        for (Object member : source) if (!removed.contains(member)) desired.add(member);
        desired.addAll(add);
        object().vmf().reflect().propertyByName(propertyName()).orElseThrow().set(desired);
    }
}
