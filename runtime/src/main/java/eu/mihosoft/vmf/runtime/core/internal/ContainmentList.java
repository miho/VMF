/*
 * Copyright 2017-2026 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
 * Copyright 2017-2019 Goethe Center for Scientific Computing, University Frankfurt. All rights reserved.
 * Copyright 2017 Samuel Michaelis. All rights reserved.
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
 * M. Hoffer, C. Poliwoda, & W. Wuttke. (2013). Visual reflection library:
 * a framework for declarative GUI programming. InComputing and Visualization in Science, 2013, 16(4),
 * 181–192. http://dx.doi.org/10.1007/s00791-014-0230-y
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vcollections.VListChangeListener;
import eu.mihosoft.vmf.runtime.core.VObject;
import vjavax.observer.Subscription;
import vjavax.observer.collection.CollectionChangeEvent;
import vjavax.observer.collection.CollectionChangeListener;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Internal adapter that validates incoming containment before an observable list changes. */
public final class ContainmentList<T> extends CheckedContainmentView<T> implements VList<T> {
    private static final ThreadLocal<AttachmentStack> PENDING = ThreadLocal.withInitial(AttachmentStack::new);
    private static final Attachment NO_ATTACHMENT = new Attachment(null, null);

    private final VList<T> observable;
    private final List<T> backing;
    private final VListChangeListener<T> internalListener;
    private List<ListListenerRegistration<T>> listeners;
    // The order in which the observers' relays are called, kept for DeliveryOrder.
    Object deliveryOrder;
    private VList<T> unmodifiable;
    private boolean syntheticRemovalPending;
    private VListChangeEvent<T> restoredLinksPending;
    private Runnable iteratorCommit;

    private ContainmentList(VObject parent, List<T> backing, VList<T> observable, VListChangeListener<T> internalListener, KeyedEntries.Rule<T> keys) {
        super(parent, observable, null, keys);
        this.backing = backing;
        this.observable = observable;
        this.internalListener = internalListener;
    }

    public static <T> VList<T> newInstance(VObject parent, List<T> backing) {
        return newInstance(parent, backing, null);
    }

    public static <T> VList<T> newInstance(VObject parent, List<T> backing, VListChangeListener<T> internalListener) {
        return newInstance(parent, backing, null, null, internalListener);
    }

    /** Creates a containment list whose entries are unique by a declared property. */
    public static <T> VList<T> newInstance(VObject parent, List<T> backing, String keyProperty,
            Function<? super T, ?> keyOf, VListChangeListener<T> internalListener) {
        KeyedEntries.Rule<T> keys = keyOf == null ? null : new KeyedEntries.Rule<>(keyProperty, keyOf, backing);
        VList<T> observable = VList.newInstance(backing);
        var result = new ContainmentList<>(parent, backing, observable, internalListener, keys);
        observable.addChangeListener(result::fire);
        return result;
    }

    private void fire(CollectionChangeEvent<T, VList<T>, VListChange<T>> event) {
        commitIteratorPosition();
        boolean internalRemoval = syntheticRemovalPending;
        if (internalRemoval) syntheticRemovalPending = false;
        var restoredLinks = restoredLinksPending;
        restoredLinksPending = null;
        int count = listenerCount();
        if (internalRemoval && count == 0) return;
        // A synthetic source removal is one stage of the destination edit.
        boolean synthetic = internalRemoval || ChangeInternal.CHANGE_TYPE_CONTAINMENT.equals(event.eventInfo());
        try (var mutation = ContainmentMutation.open(synthetic)) {
            if (count != 0) {
                var change = new Change<>(this, (VListChangeEvent<T>) event, internalRemoval, restoredLinks != null);
                fireObserved(change, restoredLinks == null ? change : restoredLinks, count);
            } else if (internalListener != null && !internalRemoval) {
                // The generated listener only reads change contents and event info.
                updateParentLinks(restoredLinks == null ? event : restoredLinks);
            }
        }
    }

    private void commitIteratorPosition() {
        var committed = iteratorCommit;
        iteratorCommit = null;
        if (committed != null) committed.run();
    }

    /** Removes entries while moving or detaching the parent link in generated code. */
    public static void removeForContainment(VList<?> list, int[] indices, VObject child) {
        list = ReferenceCollections.removingForContainment(list);
        if (list instanceof ContainmentList) {
            var containment = (ContainmentList<?>) list;
            // Generated lists use an ArrayList. A synthetic source removal has
            // no inverse callback of its own. If nobody can receive the event,
            // remove the single entry without constructing its payload.
            if (indices.length == 1 && containment.hasNoListObservers()
                    && containment.backing != null && containment.backing.getClass() == ArrayList.class) {
                containment.backing.remove(indices[0]);
                return;
            }
            // Only the next raw event is synthetic. Nested edits by its observers
            // must run normal containment maintenance and own their exceptions.
            containment.syntheticRemovalPending = true;
            try (var outgoing = containment.hasNoListObservers() ? NO_ATTACHMENT : detaching(child)) {
                containment.observable.removeAll(indices);
            } finally {
                containment.syntheticRemovalPending = false;
            }
        } else {
            String previous = list.getEventInfo();
            list.setEventInfo(ChangeInternal.CHANGE_TYPE_CONTAINMENT);
            try (var outgoing = detaching(child)) {
                list.removeAll(indices);
            } finally {
                list.setEventInfo(previous);
            }
        }
    }

    boolean hasNoListObservers() { return listenerCount() == 0; }

    private int listenerCount() {
        return listeners == null ? 0 : listeners.size();
    }

    private void fireForwarded(CollectionChangeEvent<T, VList<T>, VListChange<T>> event) {
        int count = listenerCount();
        if (count != 0) fireObserved(event, event, count);
    }

    private void fireObserved(CollectionChangeEvent<T, VList<T>, VListChange<T>> event,
                              CollectionChangeEvent<T, VList<T>, VListChange<T>> linkEvent, int count) {
        fireObserved(event, linkEvent, count, null);
    }

    private void fireObserved(CollectionChangeEvent<T, VList<T>, VListChange<T>> event,
                              CollectionChangeEvent<T, VList<T>, VListChange<T>> linkEvent, int count,
                              Attachment rangeAttachment) {
        // Capture the subscriptions before running the generated callback, as it
        // can make more edits and change subscriptions during nested notifications.
        if (count == 1) {
            var listener = listeners.get(0);
            updateParentLinks(linkEvent, rangeAttachment);
            if (capturesBeforeObservers(event)) listener.prepare((VListChangeEvent<T>) event);
            notifyListener(listener, event);
        } else {
            var snapshot = new ArrayList<>(listeners);
            updateParentLinks(linkEvent, rangeAttachment);
            if (capturesBeforeObservers(event)) {
                for (var listener : snapshot) listener.prepare((VListChangeEvent<T>) event);
            }
            for (var listener : snapshot) notifyListener(listener, event);
        }
    }

    private void updateParentLinks(CollectionChangeEvent<T, VList<T>, VListChange<T>> event,
                                   Attachment rangeAttachment) {
        try {
            updateParentLinks(event);
        } finally {
            if (rangeAttachment != null) rangeAttachment.retained = null;
        }
    }

    private void updateParentLinks(CollectionChangeEvent<T, VList<T>, VListChange<T>> event) {
        if (internalListener == null) return;
        if (!containsModels() || ChangeInternal.CHANGE_TYPE_CONTAINMENT.equals(event.eventInfo())) {
            internalListener.onChange(event);
            return;
        }
        guardEntryWrites(true);
        try {
            internalListener.onChange(event);
        } finally {
            guardEntryWrites(false);
        }
    }

    private void notifyListener(ListListenerRegistration<T> listener,
                                CollectionChangeEvent<T, VList<T>, VListChange<T>> event) {
        try {
            listener.onChange(event);
        } catch (RuntimeException | Error failure) {
            ContainmentMutation.defer(failure);
        }
    }

    private static void requireAcyclic(VObject parent, Object child, Attachment pending) {
        if (child == null) return;
        if (!(child instanceof VObjectInternalModifiable)) {
            throw new IllegalArgumentException("A contained object must be a mutable VMF object");
        }
        VObject slowerAncestor = parent;
        boolean checkForExistingCycle = false;
        for (VObject ancestor = parent; ancestor != null;) {
            if (ancestor == child) throw cycle();
            ancestor = ((VObjectInternalModifiable) ancestor)._vmf_getContainer();
            if (checkForExistingCycle) {
                slowerAncestor = ((VObjectInternalModifiable) slowerAncestor)._vmf_getContainer();
                if (ancestor != null && ancestor == slowerAncestor) {
                    throw new IllegalArgumentException("The destination already has a containment cycle");
                }
            }
            checkForExistingCycle = !checkForExistingCycle;
        }
        if (pending != null) {
            requireChildAvailable((VObject) child, pending);
            validatePendingPaths(parent, child, pending);
        }
    }

    private static void requireChildAvailable(VObject child, Attachment pending) {
        for (Attachment attachment = pending; attachment != null; attachment = attachment.previous) {
            if (attachment.outgoingChild == child || attachment.contains(child)
                    || (attachment.retained != null && attachment.retained.contains(child))) {
                throw new IllegalArgumentException("A containment edit for this child is already in progress");
            }
        }
    }

    static void requirePendingKeyChange(VObjectInternal previous, VObjectInternal child, String property, Object next) {
        for (Attachment pending = PENDING.get().active; pending != null; pending = pending.previous) {
            if (pending.parent != null && pending.parent != previous && pending.contains(child)) {
                KeyedEntries.requireKeyChange((VObjectInternal) pending.parent, child, property, next);
            }
        }
    }

    public static void requireDetachable(VObject child) {
        requireChildAvailable(child, PENDING.get().active);
    }

    /** Reserves the outgoing child while its old owner's removal notification runs. */
    public static Attachment detaching(VObject child) {
        var attachment = PENDING.get().open(null, null, null);
        attachment.outgoingChild = child;
        return attachment;
    }

    private static IllegalArgumentException cycle() {
        return new IllegalArgumentException("Containment would create a cycle");
    }

    private static void validatePendingPaths(VObject parent, Object child, Attachment pending) {
        // A detach event can run before a reparented child's new parent is visible.
        // Check pending parent edges too, while leaving state at callbacks as before.
        var visited = new IdentityHashMap<VObject, Boolean>();
        var candidates = new ArrayDeque<VObject>();
        candidates.add(parent);
        while (!candidates.isEmpty()) {
            VObject candidate = candidates.removeFirst();
            if (candidate == child) throw cycle();
            if (visited.put(candidate, Boolean.TRUE) != null) continue;
            VObject actualParent = ((VObjectInternalModifiable) candidate)._vmf_getContainer();
            if (actualParent != null) candidates.add(actualParent);
            for (Attachment attach = pending; attach != null; attach = attach.previous) {
                if (attach.contains(candidate)) candidates.add(attach.parent);
            }
        }
    }

    public static Attachment attaching(VObject parent, Object child) {
        if (child == null || parent == null) return NO_ATTACHMENT;
        var stack = PENDING.get();
        requireAcyclic(parent, child, stack.active);
        return stack.open(parent, child, null);
    }

    /** Opens the actual scalar slot before any detach or attach callbacks can run. */
    public static Attachment attaching(VObject parent, Object child, int propertyId) {
        var stack = PENDING.get();
        var pending = stack.active;
        for (Attachment attachment = pending; attachment != null; attachment = attachment.previous) {
            if (attachment.parent == parent && attachment.scalarProperty == propertyId) {
                throw new IllegalArgumentException("A containment edit for this property is already in progress");
            }
        }
        requireAcyclic(parent, child, pending);
        var attachment = stack.open(parent, child, null);
        attachment.scalarProperty = propertyId;
        return attachment;
    }

    static Attachment attachingToList(VObject parent, Object child, boolean replacing) {
        if (child == null || parent == null) return NO_ATTACHMENT;
        var stack = PENDING.get();
        var pending = stack.active;
        requireAcyclic(parent, child, pending);
        if (pending == null) {
            VObject source = ((VObjectInternalModifiable) child)._vmf_getContainer();
            // A detached child needs no removal callback. A replacement of a child
            // already in the owner's sole containment is a same-list permutation.
            if (source == null || (source == parent && replacing
                    && ((VObjectInternal) parent)._vmf_getChildrenIndices().length == 1)) {
                return NO_ATTACHMENT;
            }
        }
        return stack.open(parent, child, null);
    }

    static Attachment attachingAll(VObject parent, List<?> children) {
        if (parent == null || children.isEmpty()) return NO_ATTACHMENT;
        var stack = PENDING.get();
        requireAcyclicChildren(parent, children, stack.active);
        return stack.open(parent, null, children);
    }

    static Attachment attachingRange(VObject parent, List<?> children, Collection<?> retained) {
        var stack = PENDING.get();
        requireAcyclicChildren(parent, children, stack.active);
        var attachment = stack.open(parent, null, children);
        attachment.retained = retained;
        return attachment;
    }

    /** Checks a generated builder's staged attachments before the builder changes the target. */
    public static void validateOrdinaryBuilderChildren(VObject parent, List<?> children) {
        if (parent instanceof VObjectInternalModifiable && children != null && !children.isEmpty()) {
            requireAcyclicChildren(parent, children, PENDING.get().active);
        }
    }

    private static void requireAcyclicChildren(VObject parent, List<?> children, Attachment pending) {
        for (Object child : children) requireAcyclic(parent, child, pending);
    }

    public static void attached(VObject parent, Object child) {
        Attachment attachment = PENDING.get().active;
        if (attachment != null && attachment.parent == parent) attachment.completeChild(child);
    }

    private static final class AttachmentStack {
        private final Attachment first = new Attachment(this, null);
        private Attachment active;

        private Attachment open(VObject parent, Object child, List<?> children) {
            var saved = active;
            if (saved == null) {
                active = first;
            } else {
                if (saved.next == null) saved.next = new Attachment(this, saved);
                active = saved.next;
            }
            active.parent = parent;
            active.child = child;
            active.children = children;
            return active;
        }
    }

    /** Limits the lifetime of the proposed parent edges to a synchronous model edit. */
    public static final class Attachment implements AutoCloseable {
        private final AttachmentStack stack;
        private final Attachment previous;
        private Attachment next;
        private VObject parent;
        private Object child;
        private VObject outgoingChild;
        private List<?> children;
        private Collection<?> retained;
        private int completedChildren;
        private int scalarProperty = -1;

        private Attachment(AttachmentStack stack, Attachment previous) {
            this.stack = stack;
            this.previous = previous;
        }

        private boolean contains(VObject value) {
            if (child == value) return true;
            if (children != null) {
                for (int i = completedChildren; i < children.size(); i++) {
                    if (children.get(i) == value) return true;
                }
            }
            return false;
        }

        private void completeChild(Object value) {
            if (child == value) child = null;
            if (children != null) {
                while (completedChildren < children.size() && children.get(completedChildren) == null) {
                    completedChildren++;
                }
                if (completedChildren < children.size() && children.get(completedChildren) == value) {
                    completedChildren++;
                }
            }
        }

        public void completed() {
            if (parent != null) child = null;
            scalarProperty = -1;
        }

        @Override public void close() {
            if (stack != null) {
                stack.active = previous;
                parent = null;
                child = null;
                outgoingChild = null;
                children = null;
                retained = null;
                completedChildren = 0;
                scalarProperty = -1;
            }
        }
    }

    @Override public boolean add(T value) {
        if (hasSameParent(value) && insertExistingMembers(size(), Collections.singletonList(value))) return true;
        return super.add(value);
    }

    @Override public void add(int index, T value) {
        if (!hasSameParent(value) || !insertExistingMembers(index, Collections.singletonList(value))) super.add(index, value);
    }

    @Override public boolean addAll(Collection<? extends T> values) {
        return addAll(size(), values);
    }

    @Override public boolean addAll(int index, Collection<? extends T> values) {
        return insertExistingMembers(index, values) || insertMixedMembers(index, values) || super.addAll(index, values);
    }

    @Override public ListIterator<T> listIterator(int index) {
        if (keys != null || !containsModels()) return super.listIterator(index);
        return new OrdinaryIterator(index, null);
    }

    @Override public Iterator<T> iterator() {
        return containsModels() ? super.listIterator(0) : super.iterator();
    }

    @Override public List<T> subList(int start, int end) {
        if (keys != null || !containsModels()) return super.subList(start, end);
        return new OrdinarySubList(start, observable.subList(start, end), null);
    }

    private boolean isCurrentMember(T value) {
        if (!hasSameParent(value)) return false;
        for (T entry : backing) if (entry == value) return true;
        return false;
    }

    private void editIterator(Runnable edit, Runnable commit, boolean commitIfSilent) {
        var previous = iteratorCommit;
        iteratorCommit = commit;
        try {
            edit.run();
            if (commitIfSilent && iteratorCommit == commit) commit.run();
        } finally {
            iteratorCommit = previous;
        }
    }

    private final class OrdinaryIterator implements ListIterator<T> {
        private final OrdinarySubList view;
        private ListIterator<T> cursor;
        // A sibling view tracks structural changes to the actual backing list,
        // including synthetic transfers that do not advance the VList iterator.
        private List<T> backingCheckpoint;

        OrdinaryIterator(int index, OrdinarySubList view) {
            this.view = view;
            cursor = cursorAt(index);
            refreshCheckpoint();
        }

        private ListIterator<T> cursorAt(int index) {
            return view == null ? ContainmentList.super.listIterator(index) : view.rawIterator(index);
        }

        void refreshCheckpoint() { backingCheckpoint = backing.subList(0, 0); }

        @Override public boolean hasNext() { return cursor.hasNext(); }
        @Override public T next() { return cursor.next(); }
        @Override public boolean hasPrevious() { return cursor.hasPrevious(); }
        @Override public T previous() { return cursor.previous(); }
        @Override public int nextIndex() { return cursor.nextIndex(); }
        @Override public int previousIndex() { return cursor.previousIndex(); }
        @Override public void set(T value) { cursor.set(value); }

        @Override public void remove() { editIterator(cursor::remove, this::refreshCheckpoint, false); }

        @Override public void add(T value) {
            backingCheckpoint.size();
            if (!isCurrentMember(value)) {
                editIterator(() -> cursor.add(value), this::refreshCheckpoint, false);
                return;
            }
            // Coalescing one owning occurrence leaves the iterator at the same absolute gap.
            int gap = cursor.nextIndex();
            int absolute = gap + (view == null ? 0 : view.start);
            editIterator(() -> {
                if (view == null) insertExistingMembers(gap, Collections.singletonList(value));
                else view.add(gap, value);
            }, () -> {
                cursor = cursorAt(absolute - (view == null ? 0 : view.start));
                refreshCheckpoint();
            }, true);
        }
    }

    private final class OrdinarySubList extends CheckedContainmentView<T> {
        private final OrdinarySubList ancestor;
        private int start;

        OrdinarySubList(int start, List<T> list, OrdinarySubList ancestor) {
            super(ContainmentList.this.containingModel(), list, ContainmentList.this, null);
            this.start = start;
            this.ancestor = ancestor;
        }

        @Override public List<T> subList(int from, int to) {
            return new OrdinarySubList(start + from, list().subList(from, to), this);
        }

        @Override public Iterator<T> iterator() { return rawIterator(0); }

        @Override public ListIterator<T> listIterator(int index) { return new OrdinaryIterator(index, this); }

        ListIterator<T> rawIterator(int index) { return super.listIterator(index); }

        @Override public boolean add(T value) {
            return addAll(size(), Collections.singletonList(value));
        }

        @Override public void add(int index, T value) {
            addAll(index, Collections.singletonList(value));
        }

        @Override public boolean addAll(Collection<? extends T> values) {
            return addAll(size(), values);
        }

        @Override public boolean addAll(int index, Collection<? extends T> values) {
            if (index < 0 || index > size()) throw new IndexOutOfBoundsException("Insertion index " + index);
            if (!needsResolvedInsertion(values)) return super.addAll(index, values);
            var input = new ArrayList<>(values);

            var before = new ArrayList<>(observable);
            var after = new ArrayList<>(before);
            after.addAll(start + index, input);
            var ranges = new ArrayDeque<RangeUpdate>();
            for (var current = this; current != null; current = current.ancestor) {
                ranges.addFirst(new RangeUpdate(current, input.size()));
            }
            resolveInsertion(before, after, input, ranges);
            return true;
        }

        void bind(int from, int to) {
            start = from;
            var enclosing = ancestor == null ? observable : ancestor.list();
            int enclosingStart = ancestor == null ? 0 : ancestor.start;
            useList(enclosing.subList(from - enclosingStart, to - enclosingStart));
        }
    }

    private final class RangeUpdate {
        private final OrdinarySubList view;
        private int start;
        private int end;

        RangeUpdate(OrdinarySubList view, int insertedCount) {
            this.view = view;
            start = view.start;
            end = start + view.size() + insertedCount;
        }

        void remove(int position) {
            // An old occurrence before the view shifts both boundaries. One inside only shortens it.
            if (position < start) start--;
            if (position < end) end--;
        }

        void bind() { view.bind(start, end); }
    }

    private boolean insertExistingMembers(int index, Collection<? extends T> values) {
        if (keys != null || !containsModels() || values.isEmpty() || !values.stream().allMatch(this::hasSameParent)) return false;
        var present = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        present.addAll(observable);
        if (!present.containsAll(values)) return false;

        requireWritable();
        var before = new ArrayList<>(observable);
        var after = new ArrayList<>(before);
        after.addAll(index, values);
        // Ordinary ADD discards the first occurrence for each reinserted item.
        // When every input is already owned here, only their positions change.
        for (T value : values) {
            requireDetachable((VObject) value);
            int first = 0;
            while (after.get(first) != value) first++;
            after.remove(first);
        }

        int start = 0;
        int end = before.size();
        while (start < end && before.get(start) == after.get(start)) start++;
        if (start == end) return true;
        while (before.get(end - 1) == after.get(end - 1)) end--;
        observable.setAll(start, after.subList(start, end));
        return true;
    }

    private boolean insertMixedMembers(int index, Collection<? extends T> values) {
        if (!needsResolvedInsertion(values)) return false;
        var before = new ArrayList<>(observable);
        var after = new ArrayList<>(before);
        after.addAll(index, values);
        resolveInsertion(before, after, values);
        return true;
    }

    private boolean needsResolvedInsertion(Collection<? extends T> values) {
        if (keys != null || !containsModels() || values.isEmpty()) return false;
        var seen = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        return values.stream().anyMatch(child -> child != null && (hasSameParent(child) || !seen.add(child)));
    }

    private void resolveInsertion(List<T> before, List<T> after, Collection<? extends T> values) {
        resolveInsertion(before, after, values, Collections.emptyList());
    }

    private void resolveInsertion(List<T> before, List<T> after, Collection<? extends T> values,
                                  Collection<RangeUpdate> ranges) {
        var members = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        members.addAll(before);
        for (T child : values) {
            if (child == null || members.add(child)) continue;
            int occurrence = 0;
            while (after.get(occurrence) != child) occurrence++;
            after.remove(occurrence);
            for (var range : ranges) range.remove(occurrence);
        }

        int start = 0;
        int oldEnd = before.size();
        int newEnd = after.size();
        while (start < oldEnd && start < newEnd && before.get(start) == after.get(start)) start++;
        while (oldEnd > start && newEnd > start && before.get(oldEnd - 1) == after.get(newEnd - 1)) {
            oldEnd--;
            newEnd--;
        }
        if (start == oldEnd && start == newEnd) {
            for (var range : ranges) range.bind();
            return;
        }
        replaceRange(start, oldEnd - start, after.subList(start, newEnd), ranges);
    }

    private void replaceRange(int index, int count, List<T> replacement) {
        replaceRange(index, count, replacement, Collections.emptyList());
    }

    /** Replaces an ordinary owning list while retaining unchanged links and repeated slots. */
    public static <E> boolean replaceRecordedContents(VList<E> list, List<E> replacement) {
        if (!(list instanceof ContainmentList)) return false;
        var target = (ContainmentList<E>) list;
        if (target.keys != null || !target.containsModels()) return false;
        if (!ReferenceCollections.sameMembers(list, replacement, true)) {
            target.replaceRange(0, list.size(), replacement);
        }
        return true;
    }

    private void replaceRange(int index, int count, List<T> replacement, Collection<RangeUpdate> ranges) {
        requireWritable();
        var removed = new ArrayList<>(backing.subList(index, index + count));
        var added = new ArrayList<>(replacement);
        var retained = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        retained.addAll(backing.subList(0, index));
        retained.addAll(backing.subList(index + count, size()));
        var oldMembers = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        oldMembers.addAll(retained);
        oldMembers.addAll(removed);
        var newMembers = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        newMembers.addAll(retained);
        newMembers.addAll(added);
        for (T child : removed) if (child != null) requireDetachable((VObject) child);
        for (T child : added) if (child != null && oldMembers.contains(child)) requireDetachable((VObject) child);
        var outgoing = changedLinks(removed, newMembers, index);
        var incoming = changedLinks(added, oldMembers, index);
        retained.addAll(removed);
        for (T child : outgoing.elements()) retained.remove(child);

        try (var mutation = ContainmentMutation.open(); var attachment = attachingRange(incoming.elements(), retained)) {
            // Install final slots before an old owner's removal callback can run.
            backing.subList(index, index + count).clear();
            backing.addAll(index, added);
            // Refresh outer views first so callbacks see the installed root and nested view together.
            for (var range : ranges) range.bind();
            commitIteratorPosition();
            var linkEvent = new RangeChange<>(this, index, outgoing, incoming, getEventInfo());
            int observers = listenerCount();
            if (observers == 0) {
                updateParentLinks(linkEvent, attachment);
            } else {
                var event = new RangeChange<>(this, index, range(index, removed), range(index, added), getEventInfo());
                fireObserved(event, linkEvent, observers, attachment);
            }
        }
    }

    private static <T> VListChange<T> changedLinks(List<T> values, Collection<T> existing, int start) {
        var changed = new ArrayList<T>();
        var indices = new int[values.size()];
        for (int offset = 0; offset < values.size(); offset++) {
            T child = values.get(offset);
            // The supplied identity set also deduplicates multiple changed slots belonging to one child.
            if (child != null && existing.add(child)) {
                indices[changed.size()] = start + offset;
                changed.add(child);
            }
        }
        return VListChange.newInstance(Arrays.copyOf(indices, changed.size()), changed);
    }

    private static <T> VListChange<T> range(int start, List<T> values) {
        var indices = new int[values.size()];
        for (int offset = 0; offset < values.size(); offset++) indices[offset] = start + offset;
        return VListChange.newInstance(indices, Collections.unmodifiableList(values));
    }

    /** Whether the independent native ranges describe one owning-list edit. */
    public static boolean isRangeChange(VListChangeEvent<?> event) { return event instanceof RangeChange; }

    static boolean capturesBeforeObservers(CollectionChangeEvent<?, ?, ?> event) {
        // The list positions are already committed, including a source removal
        // that precedes inverse repair and destination adoption.
        return event.source() instanceof ContainmentList<?>
                && (event.wasAdded() || event.wasRemoved());
    }

    static final class RangeChange<T> implements VListChangeEvent<T> {
        private final ContainmentList<T> source;
        private final int start;
        private final VListChange<T> removed;
        private final VListChange<T> added;
        private final String info;
        private final int sizeAfter;

        private RangeChange(ContainmentList<T> source, int start, VListChange<T> removed, VListChange<T> added, String info) {
            this.source = source;
            this.start = start;
            this.removed = removed;
            this.added = added;
            this.info = info;
            this.sizeAfter = source.size();
        }

        boolean canUndo() {
            if (added.elements().isEmpty() && source.size() != sizeAfter) return false;
            if (start > source.size() - added.elements().size()) return false;
            for (int i = 0; i < added.elements().size(); i++) {
                if (source.get(start + i) != added.elements().get(i)) return false;
            }
            return true;
        }

        void replay(boolean undo) {
            var current = undo ? added : removed;
            var target = undo ? removed : added;
            source.replaceRange(start, current.elements().size(), target.elements());
        }

        @Override public VList<T> source() { return source; }
        @Override public VListChange<T> added() { return added; }
        @Override public VListChange<T> removed() { return removed; }
        @Override public boolean wasAdded() { return added.hasChanges(); }
        @Override public boolean wasRemoved() { return removed.hasChanges(); }
        @Override public boolean wasSet() { return wasAdded() && wasRemoved(); }
        @Override public String eventInfo() { return info; }
        @Override public String toStringWithDetails() {
            return "removed " + Arrays.toString(removed.indices()) + ": " + removed.elements()
                    + ", added " + Arrays.toString(added.indices()) + ": " + added.elements();
        }
    }

    @Override public boolean addAll(int[] indices, Collection<? extends T> values) {
        requireWritable();
        var copy = new ArrayList<T>(values);
        if (keys != null) keys.validate(Collections.emptyList(), copy);
        if (needsResolvedInsertion(copy)) {
            Objects.requireNonNull(indices, "indices");
            if (indices.length != copy.size()) {
                throw new IllegalArgumentException("Index and element counts differ");
            }
            long finalSize = (long) size() + indices.length;
            var positions = new TreeMap<Integer, T>();
            for (int i = 0; i < indices.length; i++) {
                int index = indices[i];
                if (index < 0 || index >= finalSize) throw new IndexOutOfBoundsException("Insertion index " + index);
                if (positions.containsKey(index)) throw new IllegalArgumentException("Duplicate insertion index " + index);
                positions.put(index, copy.get(i));
            }
            var before = new ArrayList<>(observable);
            var after = new ArrayList<>(before);
            positions.forEach((position, child) -> after.add(position, child));
            resolveInsertion(before, after, positions.values());
            return true;
        }
        try (var attachment = attachingAll(copy)) {
            return observable.addAll(indices, copy);
        }
    }

    @SuppressWarnings("unchecked")
    boolean canRestoreKeyedEntries(List<?> values) {
        if (keys == null) return true;
        try {
            keys.validate(Collections.emptyList(), (List<T>) values);
            return true;
        } catch (IllegalArgumentException invalidKey) {
            return false;
        }
    }

    /** Inserts recorded positions exactly when ordinary containment ADD would coalesce an alias. */
    public static <T> boolean insertRecordedAliases(VList<T> list, int[] indices, List<T> values) {
        return list instanceof ContainmentList
                && ((ContainmentList<T>) list).insertRecordedAliases(indices, values);
    }

    static boolean isAliasRestoration(CollectionChangeEvent<?, ?, ?> event) {
        return event instanceof Change && ((Change<?>) event).aliasRestoration;
    }

    private boolean insertRecordedAliases(int[] indices, List<T> values) {
        if (keys != null || !containsModels()) return false;
        var present = Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
        if (values.stream().anyMatch(this::hasSameParent)) present.addAll(observable);
        var attachments = new ArrayList<T>();
        var attachmentIndices = new int[indices.length];
        boolean restoresAlias = false;
        for (int i = 0; i < indices.length; i++) {
            T child = values.get(i);
            if (child == null) continue;
            if (present.add(child)) {
                attachmentIndices[attachments.size()] = indices[i];
                attachments.add(child);
            } else {
                requireDetachable((VObject) child);
                restoresAlias = true;
            }
        }
        if (!restoresAlias) return false;

        requireWritable();
        // The public change includes every restored slot. Inverse maintenance
        // attaches each new child once and leaves retained ownership links alone.
        try (var attachment = attachingAll(attachments)) {
            restoredLinksPending = VListChangeEvent.getAddedEvent(
                    this, Arrays.copyOf(attachmentIndices, attachments.size()), attachments);
            try {
                observable.addAll(indices, values);
            } finally {
                restoredLinksPending = null;
            }
        }
        return true;
    }

    @Override public Collection<T> setAll(int index, Collection<T> values) {
        requireWritable();
        var copy = new ArrayList<>(values);
        if (keys != null) keys.validate(subList(index, index + copy.size()), copy);
        try (var attachment = attachingAll(copy)) {
            return observable.setAll(index, copy);
        }
    }

    @Override public boolean removeAll(int... indices) {
        requireWritable();
        return observable.removeAll(indices);
    }

    @Override public void setEventInfo(String value) { observable.setEventInfo(value); }

    @Override public String getEventInfo() { return observable.getEventInfo(); }

    @Override public VList<T> asUnmodifiable() {
        if (unmodifiable == null) {
            var view = new ContainmentList<>(null, null, VList.newInstance(Collections.unmodifiableList(observable)), null, null);
            addChangeListener(view::fireForwarded);
            unmodifiable = view;
        }
        return unmodifiable;
    }

    @Override public Subscription addChangeListener(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        return observe(listener, null);
    }

    Subscription observe(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener,
                         Consumer<VListChangeEvent<T>> prepare) {
        if (listeners == null) listeners = new ArrayList<>();
        var registration = new ListListenerRegistration<>(listener, prepare);
        listeners.add(registration);
        return () -> listeners.remove(registration);
    }

    @Override public boolean removeChangeListener(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        if (listeners == null) return false;
        int index = ListListenerRegistration.indexOf(listeners, listener);
        if (index < 0) return false;
        listeners.remove(index);
        return true;
    }

    private static final class Change<T> implements VListChangeEvent<T>, DeliveryOrder.Stamped {
        private final VList<T> source;
        private final VListChangeEvent<T> event;
        private final boolean internalRemoval;
        private final boolean aliasRestoration;
        private long deliveryStamp;

        @Override public long deliveryStamp() { return deliveryStamp; }
        @Override public void deliveryStamp(long stamp) { deliveryStamp = stamp; }

        private Change(VList<T> source, VListChangeEvent<T> event, boolean internalRemoval, boolean aliasRestoration) {
            this.source = source;
            this.event = event;
            this.internalRemoval = internalRemoval;
            this.aliasRestoration = aliasRestoration;
        }

        @Override public VList<T> source() { return source; }
        @Override public VListChange<T> added() { return event.added(); }
        @Override public VListChange<T> removed() { return event.removed(); }
        @Override public boolean wasAdded() { return event.wasAdded(); }
        @Override public boolean wasRemoved() { return event.wasRemoved(); }
        @Override public boolean wasSet() { return event.wasSet(); }
        @Override public String eventInfo() {
            return internalRemoval ? ChangeInternal.CHANGE_TYPE_CONTAINMENT : event.eventInfo();
        }
        @Override public String toString() { return event.toString(); }
        @Override public String toStringWithDetails() { return event.toStringWithDetails(); }
    }
}

/** Applies the same preflight to ordinary list API calls and views of the observable list. */
class CheckedContainmentView<T> extends AbstractList<T> {
    private final VObject parent;
    private List<T> list;
    private final CheckedContainmentView<?> owner;
    final KeyedEntries.Rule<T> keys;
    private boolean updatingParentLinks;

    CheckedContainmentView(VObject parent, List<T> list, CheckedContainmentView<?> owner, KeyedEntries.Rule<T> keys) {
        this.keys = keys;
        this.parent = parent;
        this.list = list;
        this.owner = owner == null ? this : owner;
    }

    final void requireKeyChange(Object child, String property, Object next) {
        if (keys != null) keys.requireKeyChange(child, property, next);
    }

    final boolean containsModels() { return parent != null; }

    final VObject containingModel() { return parent; }

    final List<T> list() { return list; }

    final void useList(List<T> list) { this.list = list; }

    final boolean hasSameParent(Object child) {
        return child instanceof VObjectInternalModifiable && ((VObjectInternalModifiable) child)._vmf_getContainer() == parent;
    }

    final void guardEntryWrites(boolean guard) { owner.updatingParentLinks = guard; }

    final void requireWritable() {
        if (owner.updatingParentLinks) {
            throw new IllegalArgumentException("A containment edit for this list is already in progress");
        }
    }

    final ContainmentList.Attachment attachingAll(List<?> values) {
        return ContainmentList.attachingAll(parent, values);
    }

    final ContainmentList.Attachment attachingRange(List<?> values, Collection<?> retained) {
        return ContainmentList.attachingRange(parent, values, retained);
    }

    @Override public boolean add(T value) {
        requireWritable();
        if (keys != null) keys.validate(Collections.emptyList(), Collections.singletonList(value));
        try (var attachment = ContainmentList.attachingToList(parent, value, false)) {
            return list.add(value);
        }
    }

    @Override public void add(int index, T value) {
        requireWritable();
        if (keys != null) keys.validate(Collections.emptyList(), Collections.singletonList(value));
        try (var attachment = ContainmentList.attachingToList(parent, value, false)) {
            list.add(index, value);
        }
    }

    @Override public boolean addAll(Collection<? extends T> values) {
        requireWritable();
        var copy = new ArrayList<T>(values);
        if (keys != null) keys.validate(Collections.emptyList(), copy);
        try (var attachment = attachingAll(copy)) {
            return list.addAll(copy);
        }
    }

    @Override public boolean addAll(int index, Collection<? extends T> values) {
        requireWritable();
        var copy = new ArrayList<T>(values);
        if (keys != null) keys.validate(Collections.emptyList(), copy);
        try (var attachment = attachingAll(copy)) {
            return list.addAll(index, copy);
        }
    }

    @Override public T set(int index, T value) {
        requireWritable();
        if (keys != null) keys.validate(Collections.singletonList(get(index)), Collections.singletonList(value));
        try (var attachment = ContainmentList.attachingToList(parent, value, true)) {
            return list.set(index, value);
        }
    }

    @Override public void replaceAll(UnaryOperator<T> operator) {
        requireWritable();
        Objects.requireNonNull(operator);
        if (isEmpty()) return;
        if (parent == null) {
            list.replaceAll(operator);
            return;
        }
        var replacements = new ArrayList<T>(size());
        for (T previous : list) replacements.add(operator.apply(previous));
        if (keys != null) keys.validate(list, replacements);
        var iterator = replacements.iterator();
        try (var attachment = attachingAll(replacements)) {
            list.replaceAll(ignored -> iterator.next());
        }
    }

    @Override public List<T> subList(int start, int end) {
        return new CheckedContainmentView<>(parent, list.subList(start, end), owner, keys);
    }

    @Override public Iterator<T> iterator() { return containsModels() ? listIterator(0) : list.iterator(); }

    @Override public ListIterator<T> listIterator(int index) {
        var iterator = list.listIterator(index);
        return new ListIterator<>() {
            private T last;
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public T next() { return last = iterator.next(); }
            @Override public boolean hasPrevious() { return iterator.hasPrevious(); }
            @Override public T previous() { return last = iterator.previous(); }
            @Override public int nextIndex() { return iterator.nextIndex(); }
            @Override public int previousIndex() { return iterator.previousIndex(); }
            @Override public void remove() {
                requireWritable();
                iterator.remove();
            }
            @Override public void set(T value) {
                requireWritable();
                if (keys != null) keys.validate(Collections.singletonList(last), Collections.singletonList(value));
                try (var attachment = ContainmentList.attachingToList(parent, value, true)) {
                    iterator.set(value);
                    last = value;
                }
            }
            @Override public void add(T value) {
                requireWritable();
                if (keys != null) keys.validate(Collections.emptyList(), Collections.singletonList(value));
                try (var attachment = ContainmentList.attachingToList(parent, value, false)) {
                    iterator.add(value);
                }
            }
        };
    }

    @Override public int size() { return list.size(); }
    @Override public T get(int index) { return list.get(index); }
    @Override public boolean isEmpty() { return list.isEmpty(); }
    @Override public boolean contains(Object value) { return list.contains(ReferenceCollections.identity(value)); }
    @Override public boolean containsAll(Collection<?> values) { return ReferenceAliasList.containsAll(list, values); }
    @Override public int indexOf(Object value) { return list.indexOf(ReferenceCollections.identity(value)); }
    @Override public int lastIndexOf(Object value) { return list.lastIndexOf(ReferenceCollections.identity(value)); }
    @Override public Object[] toArray() { return list.toArray(); }
    @Override public <U> U[] toArray(U[] result) { return list.toArray(result); }
    @Override public boolean remove(Object value) { requireWritable(); return list.remove(ReferenceCollections.identity(value)); }
    @Override public T remove(int index) { requireWritable(); return list.remove(index); }
    @Override public boolean removeAll(Collection<?> values) { requireWritable(); return list.removeAll(values); }
    @Override public boolean retainAll(Collection<?> values) { requireWritable(); return list.retainAll(values); }
    @Override public boolean removeIf(Predicate<? super T> predicate) { requireWritable(); return list.removeIf(predicate); }
    @Override public void sort(Comparator<? super T> comparator) { requireWritable(); list.sort(comparator); }
    @Override public void clear() { requireWritable(); list.clear(); }
    @Override public int hashCode() { return list.hashCode(); }
}
