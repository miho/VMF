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
import eu.mihosoft.vcollections.VListChangeSupport;
import eu.mihosoft.vmf.runtime.core.VObject;
import vjavax.observer.Subscription;
import vjavax.observer.collection.CollectionChangeListener;

import java.util.AbstractList;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Internal adapter that validates incoming containment before an observable list changes. */
public final class ContainmentList<T> extends CheckedContainmentView<T> implements VList<T> {
    private static final ThreadLocal<Attachment> PENDING = new ThreadLocal<>();
    private static final Attachment NO_ATTACHMENT = new Attachment(null, null, null, null);

    private final VList<T> observable;
    private final VListChangeSupport<T> listeners = new VListChangeSupport<>();
    private VList<T> unmodifiable;

    private ContainmentList(VObject parent, VList<T> observable) {
        super(parent, observable);
        this.observable = observable;
    }

    public static <T> VList<T> newInstance(VObject parent, List<T> backing) {
        VList<T> observable = VList.newInstance(backing);
        var result = new ContainmentList<>(parent, observable);
        observable.addChangeListener(event -> result.listeners.fireEvent(new Change<>(result, (VListChangeEvent<T>) event)));
        return result;
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
        if (pending != null) validatePendingPaths(parent, child, pending);
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
        Attachment previous = PENDING.get();
        requireAcyclic(parent, child, previous);
        var attachment = new Attachment(parent, child, null, previous);
        PENDING.set(attachment);
        return attachment;
    }

    static Attachment attachingAll(VObject parent, List<?> children) {
        if (parent == null || children.isEmpty()) return NO_ATTACHMENT;
        Attachment previous = PENDING.get();
        for (Object child : children) requireAcyclic(parent, child, previous);
        var attachment = new Attachment(parent, null, children, previous);
        PENDING.set(attachment);
        return attachment;
    }

    /** Limits the lifetime of the proposed parent edges to a synchronous model edit. */
    public static final class Attachment implements AutoCloseable {
        private final VObject parent;
        private final Object child;
        private final List<?> children;
        private final Attachment previous;

        private Attachment(VObject parent, Object child, List<?> children, Attachment previous) {
            this.parent = parent;
            this.child = child;
            this.children = children;
            this.previous = previous;
        }

        private boolean contains(VObject value) {
            if (child == value) return true;
            if (children != null) {
                for (Object item : children) {
                    if (item == value) return true;
                }
            }
            return false;
        }

        @Override public void close() {
            if (parent != null) PENDING.set(previous);
        }
    }

    @Override public boolean addAll(int[] indices, Collection<? extends T> values) {
        var copy = new ArrayList<T>(values);
        try (var attachment = attachingAll(copy)) {
            return observable.addAll(indices, copy);
        }
    }

    @Override public Collection<T> setAll(int index, Collection<T> values) {
        var copy = new ArrayList<>(values);
        try (var attachment = attachingAll(copy)) {
            return observable.setAll(index, copy);
        }
    }

    @Override public boolean removeAll(int... indices) { return observable.removeAll(indices); }

    @Override public void setEventInfo(String value) { observable.setEventInfo(value); }

    @Override public String getEventInfo() { return observable.getEventInfo(); }

    @Override public VList<T> asUnmodifiable() {
        if (unmodifiable == null) {
            var view = new ContainmentList<>(null, VList.newInstance(Collections.unmodifiableList(observable)));
            listeners.addChangeListener(view.listeners::fireEvent);
            unmodifiable = view;
        }
        return unmodifiable;
    }

    @Override public Subscription addChangeListener(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        return listeners.addChangeListener(listener);
    }

    @Override public boolean removeChangeListener(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        return listeners.removeChangeListener(listener);
    }

    private static final class Change<T> implements VListChangeEvent<T> {
        private final VList<T> source;
        private final VListChangeEvent<T> event;

        private Change(VList<T> source, VListChangeEvent<T> event) {
            this.source = source;
            this.event = event;
        }

        @Override public VList<T> source() { return source; }
        @Override public VListChange<T> added() { return event.added(); }
        @Override public VListChange<T> removed() { return event.removed(); }
        @Override public boolean wasAdded() { return event.wasAdded(); }
        @Override public boolean wasRemoved() { return event.wasRemoved(); }
        @Override public boolean wasSet() { return event.wasSet(); }
        @Override public String eventInfo() { return event.eventInfo(); }
        @Override public String toString() { return event.toString(); }
        @Override public String toStringWithDetails() { return event.toStringWithDetails(); }
    }
}

/** Applies the same preflight to ordinary list API calls and views of the observable list. */
class CheckedContainmentView<T> extends AbstractList<T> {
    private final VObject parent;
    private final List<T> list;

    CheckedContainmentView(VObject parent, List<T> list) {
        this.parent = parent;
        this.list = list;
    }

    final ContainmentList.Attachment attachingAll(List<?> values) {
        return ContainmentList.attachingAll(parent, values);
    }

    @Override public boolean add(T value) {
        try (var attachment = ContainmentList.attaching(parent, value)) {
            return list.add(value);
        }
    }

    @Override public void add(int index, T value) {
        try (var attachment = ContainmentList.attaching(parent, value)) {
            list.add(index, value);
        }
    }

    @Override public boolean addAll(Collection<? extends T> values) {
        var copy = new ArrayList<T>(values);
        try (var attachment = attachingAll(copy)) {
            return list.addAll(copy);
        }
    }

    @Override public boolean addAll(int index, Collection<? extends T> values) {
        var copy = new ArrayList<T>(values);
        try (var attachment = attachingAll(copy)) {
            return list.addAll(index, copy);
        }
    }

    @Override public T set(int index, T value) {
        try (var attachment = ContainmentList.attaching(parent, value)) {
            return list.set(index, value);
        }
    }

    @Override public void replaceAll(UnaryOperator<T> operator) {
        Objects.requireNonNull(operator);
        if (parent == null) {
            list.replaceAll(operator);
            return;
        }
        var replacements = new ArrayList<T>(size());
        for (T previous : list) replacements.add(operator.apply(previous));
        var iterator = replacements.iterator();
        try (var attachment = attachingAll(replacements)) {
            list.replaceAll(ignored -> iterator.next());
        }
    }

    @Override public List<T> subList(int start, int end) {
        return new CheckedContainmentView<>(parent, list.subList(start, end));
    }

    @Override public Iterator<T> iterator() { return list.iterator(); }

    @Override public ListIterator<T> listIterator(int index) {
        var iterator = list.listIterator(index);
        return new ListIterator<>() {
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public T next() { return iterator.next(); }
            @Override public boolean hasPrevious() { return iterator.hasPrevious(); }
            @Override public T previous() { return iterator.previous(); }
            @Override public int nextIndex() { return iterator.nextIndex(); }
            @Override public int previousIndex() { return iterator.previousIndex(); }
            @Override public void remove() { iterator.remove(); }
            @Override public void set(T value) {
                try (var attachment = ContainmentList.attaching(parent, value)) {
                    iterator.set(value);
                }
            }
            @Override public void add(T value) {
                try (var attachment = ContainmentList.attaching(parent, value)) {
                    iterator.add(value);
                }
            }
        };
    }

    @Override public int size() { return list.size(); }
    @Override public T get(int index) { return list.get(index); }
    @Override public boolean isEmpty() { return list.isEmpty(); }
    @Override public boolean contains(Object value) { return list.contains(value); }
    @Override public boolean containsAll(Collection<?> values) { return list.containsAll(values); }
    @Override public int indexOf(Object value) { return list.indexOf(value); }
    @Override public int lastIndexOf(Object value) { return list.lastIndexOf(value); }
    @Override public Object[] toArray() { return list.toArray(); }
    @Override public <U> U[] toArray(U[] result) { return list.toArray(result); }
    @Override public boolean remove(Object value) { return list.remove(value); }
    @Override public T remove(int index) { return list.remove(index); }
    @Override public boolean removeAll(Collection<?> values) { return list.removeAll(values); }
    @Override public boolean retainAll(Collection<?> values) { return list.retainAll(values); }
    @Override public boolean removeIf(Predicate<? super T> predicate) { return list.removeIf(predicate); }
    @Override public void sort(Comparator<? super T> comparator) { list.sort(comparator); }
    @Override public void clear() { list.clear(); }
    @Override public int hashCode() { return list.hashCode(); }
}
