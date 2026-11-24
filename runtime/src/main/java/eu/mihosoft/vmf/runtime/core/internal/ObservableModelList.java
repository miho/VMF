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
import vjavax.observer.Subscription;
import vjavax.observer.collection.CollectionChangeEvent;
import vjavax.observer.collection.CollectionChangeListener;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Internal generated-model list that completes synchronous delivery before throwing. */
public class ObservableModelList<T> extends AbstractList<T> implements VList<T> {
    private final VList<T> observable;
    private List<CollectionChangeListener<T, VList<T>, VListChange<T>>> listeners = Collections.emptyList();
    private Subscription relay;
    private VList<T> unmodifiable;
    private boolean crossReferencePending;

    ObservableModelList(VList<T> observable) {
        this.observable = observable;
    }

    public static <T> VList<T> newInstance(List<T> backing) {
        return new ObservableModelList<>(VList.newInstance(backing));
    }

    /** Returns true when a replacement only reorders the existing edges, counting identities and duplicates. */
    public static boolean permutesIdentities(java.util.List<?> before, java.util.List<?> after) {
        if (before.size() != after.size()) return false;
        if (before.size() == 1) return before.get(0) == after.get(0);
        java.util.IdentityHashMap<Object, Integer> counts = new java.util.IdentityHashMap<>();
        for (Object edge : before) counts.merge(edge, 1, Integer::sum);
        for (Object edge : after) {
            Integer count = counts.get(edge);
            if (count == null) return false;
            if (count == 1) counts.remove(edge);
            else counts.put(edge, count - 1);
        }
        return true;
    }

    public static <T> void addCrossReference(VList<T> list, T value) {
        crossReference(list, () -> list.add(value));
    }

    public static <T> void removeCrossReference(VList<T> list, T value) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == value) { removeCrossReference(list, i); return; }
        }
    }

    /** Removes all occurrences mirrored by one unique opposite edge in a single native change. */
    public static void removeAllCrossReferences(VList<?> list, Object target) {
        int[] positions = java.util.stream.IntStream.range(0, list.size())
                .filter(i -> list.get(i) == target).toArray();
        if (positions.length != 0) crossReference(list, () -> list.removeAll(positions));
    }

    public static void removeCrossReference(VList<?> list, int index) {
        crossReference(list, () -> list.remove(index));
    }

    private static void crossReference(VList<?> list, Runnable edit) {
        if (list instanceof ObservableModelList) {
            var modelList = (ObservableModelList<?>) list;
            modelList.crossReferencePending = true;
            try {
                edit.run();
            } finally {
                modelList.crossReferencePending = false;
            }
        } else {
            String eventInfo = list.getEventInfo();
            list.setEventInfo(ChangeInternal.CHANGE_TYPE_CROSS_REF);
            try {
                edit.run();
            } finally {
                list.setEventInfo(eventInfo);
            }
        }
    }

    private void fire(CollectionChangeEvent<T, VList<T>, VListChange<T>> event) {
        // Consume the synthetic tag before observers can make a nested public edit.
        boolean crossReference = crossReferencePending;
        if (crossReference) crossReferencePending = false;
        if (!listeners.isEmpty()) {
            dispatch(new ModelListEvent<>(this, (VListChangeEvent<T>) event, crossReference));
        }
    }

    private void dispatch(CollectionChangeEvent<T, VList<T>, VListChange<T>> event) {
        if (listeners.isEmpty()) return;
        if (listeners.size() == 1) {
            listeners.get(0).onChange(event);
            return;
        }

        Throwable failure = null;
        // Registration publishes a new list, so nested subscription changes
        // cannot alter the current delivery.
        for (var listener : listeners) {
            try {
                listener.onChange(event);
            } catch (RuntimeException | Error ex) {
                failure = ObserverFailures.add(failure, ex);
            }
        }
        ObserverFailures.rethrow(failure);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Subscription addChangeListener(
            CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        var typed = (CollectionChangeListener<T, VList<T>, VListChange<T>>) listener;
        if (listeners.isEmpty()) {
            relay = observable.addChangeListener(this::fire);
            listeners = Collections.singletonList(typed);
        } else {
            var updated = new ArrayList<>(listeners);
            updated.add(typed);
            listeners = updated;
        }
        return () -> removeChangeListener(listener);
    }

    @Override
    public boolean removeChangeListener(
            CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        int index = listeners.indexOf(listener);
        if (index < 0) return false;
        if (listeners.size() == 1) {
            listeners = Collections.emptyList();
            relay.unsubscribe();
            relay = null;
        } else if (listeners.size() == 2) {
            listeners = Collections.singletonList(listeners.get(1 - index));
        } else {
            var updated = new ArrayList<>(listeners);
            updated.remove(index);
            listeners = updated;
        }
        return true;
    }

    @Override public VList<T> asUnmodifiable() {
        return unmodifiableView(false);
    }

    final VList<T> unmodifiableView(boolean projectEventSource) {
        if (unmodifiable == null) {
            var view = new ObservableModelList<>(VList.newInstance(Collections.unmodifiableList(observable)));
            view.unmodifiable = view;
            // Raw VList views forward the original edit and its source. A generated read-only projection
            // provides its own event source so an observer cannot reach the mutable model list through it.
            if (projectEventSource) {
                addChangeListener(event -> view.dispatch(new ModelListEvent<>(view, (VListChangeEvent<T>) event, false)));
            } else {
                addChangeListener(view::dispatch);
            }
            unmodifiable = view;
        }
        return unmodifiable;
    }

    @Override public boolean add(T value) { return observable.add(value); }
    @Override public void add(int index, T value) { observable.add(index, value); }
    @Override public boolean addAll(Collection<? extends T> values) { return observable.addAll(values); }
    @Override public boolean addAll(int index, Collection<? extends T> values) { return observable.addAll(index, values); }
    @Override public boolean addAll(int[] indices, Collection<? extends T> values) { return observable.addAll(indices, values); }
    @Override public T set(int index, T value) { return observable.set(index, value); }
    @Override public Collection<T> setAll(int index, Collection<T> values) { return observable.setAll(index, values); }
    @Override public int size() { return observable.size(); }
    @Override public T get(int index) { return observable.get(index); }
    @Override public boolean isEmpty() { return observable.isEmpty(); }
    @Override public boolean contains(Object value) { return observable.contains(value); }
    @Override public boolean containsAll(Collection<?> values) { return observable.containsAll(values); }
    @Override public int indexOf(Object value) { return observable.indexOf(value); }
    @Override public int lastIndexOf(Object value) { return observable.lastIndexOf(value); }
    @Override public Object[] toArray() { return observable.toArray(); }
    @Override public <U> U[] toArray(U[] result) { return observable.toArray(result); }
    @Override public boolean remove(Object value) { return observable.remove(value); }
    @Override public T remove(int index) { return observable.remove(index); }
    @Override public boolean removeAll(int... indices) { return observable.removeAll(indices); }
    @Override public boolean removeAll(Collection<?> values) { return observable.removeAll(values); }
    @Override public boolean retainAll(Collection<?> values) { return observable.retainAll(values); }
    @Override public boolean removeIf(Predicate<? super T> predicate) { return observable.removeIf(predicate); }
    @Override public void replaceAll(UnaryOperator<T> operator) { observable.replaceAll(operator); }
    @Override public void sort(Comparator<? super T> comparator) { observable.sort(comparator); }
    @Override public void clear() { observable.clear(); }
    @Override public Iterator<T> iterator() { return observable.iterator(); }
    @Override public ListIterator<T> listIterator() { return observable.listIterator(); }
    @Override public ListIterator<T> listIterator(int index) { return observable.listIterator(index); }
    @Override public List<T> subList(int from, int to) { return observable.subList(from, to); }
    @Override public String getEventInfo() { return observable.getEventInfo(); }
    @Override public void setEventInfo(String value) { observable.setEventInfo(value); }
    @Override public boolean equals(Object other) { return other == this || observable.equals(other); }
    @Override public int hashCode() { return observable.hashCode(); }

    private static final class ModelListEvent<T> implements VListChangeEvent<T> {
        private final VList<T> source;
        private final VListChangeEvent<T> event;
        private final boolean crossReference;

        private ModelListEvent(VList<T> source, VListChangeEvent<T> event, boolean crossReference) {
            this.source = source;
            this.event = event;
            this.crossReference = crossReference;
        }

        @Override public VList<T> source() { return source; }
        @Override public VListChange<T> added() { return event.added(); }
        @Override public VListChange<T> removed() { return event.removed(); }
        @Override public boolean wasAdded() { return event.wasAdded(); }
        @Override public boolean wasRemoved() { return event.wasRemoved(); }
        @Override public boolean wasSet() { return event.wasSet(); }
        @Override public String eventInfo() {
            return crossReference ? ChangeInternal.CHANGE_TYPE_CROSS_REF : event.eventInfo();
        }
        @Override public String toString() { return event.toString(); }
        @Override public String toStringWithDetails() { return event.toStringWithDetails(); }
    }
}
