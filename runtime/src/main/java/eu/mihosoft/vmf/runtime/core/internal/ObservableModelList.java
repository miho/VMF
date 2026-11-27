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
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Internal generated-model list that completes synchronous delivery before throwing. */
public class ObservableModelList<T> extends AbstractList<T> implements VList<T> {
    private final VList<T> observable;
    private final boolean ordinary;
    private final boolean modelElements;
    private enum ReciprocalOpposite { NONE, COLLECTION, SCALAR }

    private final ReciprocalOpposite reciprocalOpposite;
    private List<ListListenerRegistration<T>> listeners = Collections.emptyList();
    private Subscription relay;
    private VList<T> unmodifiable;
    private boolean crossReferencePending;

    ObservableModelList(VList<T> observable) {
        this(observable, false, false);
    }

    private ObservableModelList(VList<T> observable, boolean ordinary, boolean modelElements) {
        this(observable, ordinary, modelElements, ReciprocalOpposite.NONE);
    }

    private ObservableModelList(VList<T> observable, boolean ordinary, boolean modelElements,
                                ReciprocalOpposite reciprocalOpposite) {
        this.observable = observable;
        this.ordinary = ordinary;
        this.modelElements = modelElements;
        this.reciprocalOpposite = reciprocalOpposite;
    }

    public static <T> VList<T> newInstance(List<T> backing) {
        return new ObservableModelList<>(VList.newInstance(backing), false, false);
    }

    public static <T> VList<T> newReferenceInstance(List<T> backing) {
        return new ObservableModelList<>(VList.newInstance(backing), false, true);
    }

    /** A reciprocal positional source whose opposite updates are inverse-only. */
    public static <T> VList<T> newCollectionReciprocalSequence(List<T> backing) {
        return new ObservableModelList<>(VList.newInstance(backing), false, true, ReciprocalOpposite.COLLECTION);
    }

    /** A positional source that keeps only one occurrence per singular opposite. */
    public static <T> VList<T> newScalarReciprocalSequence(List<T> backing) {
        return new ObservableModelList<>(VList.newInstance(backing), false, true, ReciprocalOpposite.SCALAR);
    }

    public static boolean hasScalarReciprocalOpposite(VList<?> source) {
        return source instanceof ObservableModelList
                && ((ObservableModelList<?>) source).reciprocalOpposite == ReciprocalOpposite.SCALAR;
    }

    boolean hasReciprocalCollectionOpposite() {
        return reciprocalOpposite == ReciprocalOpposite.COLLECTION;
    }

    /** Creates a positional list without an opposite that can be captured before callbacks. */
    public static <T> VList<T> newOrdinaryInstance(List<T> backing) {
        return newOrdinaryInstance(backing, false);
    }

    public static <T> VList<T> newOrdinaryInstance(List<T> backing, boolean modelElements) {
        return new ObservableModelList<>(VList.newInstance(backing), true, modelElements);
    }

    boolean isOrdinary() {
        return ordinary;
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

    /** Compares the current reverse occurrence count to the live initiating list. */
    public static int compareOppositeOccurrences(Iterable<?> source, Object value, Iterable<?> opposite, Object owner) {
        return Integer.compare(identityCount(opposite, owner), identityCount(source, value));
    }

    private static int identityCount(Iterable<?> entries, Object identity) {
        int count = 0;
        for (Object entry : entries) {
            if (entry == identity) count++;
        }
        return count;
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
        var snapshot = listeners;
        if (snapshot.isEmpty()) return;
        // Prepare portable records before any direct or model listener can edit the list again.
        if (snapshot.size() == 1) {
            var listener = snapshot.get(0);
            listener.prepare((VListChangeEvent<T>) event);
            listener.onChange(event);
            return;
        }
        for (var listener : snapshot) listener.prepare((VListChangeEvent<T>) event);

        Throwable failure = null;
        // Registration publishes a new list, so nested subscription changes
        // cannot alter the current delivery.
        for (var listener : snapshot) {
            try {
                listener.onChange(event);
            } catch (RuntimeException | Error ex) {
                failure = ObserverFailures.add(failure, ex);
            }
        }
        ObserverFailures.rethrow(failure);
    }

    @Override
    public Subscription addChangeListener(
            CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        return observe(listener, null);
    }

    Subscription observe(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener,
                         Consumer<VListChangeEvent<T>> prepare) {
        var registration = new ListListenerRegistration<>(listener, prepare);
        if (listeners.isEmpty()) {
            relay = observable.addChangeListener(this::fire);
            listeners = Collections.singletonList(registration);
        } else {
            var updated = new ArrayList<>(listeners);
            updated.add(registration);
            listeners = updated;
        }
        return () -> removeAt(listeners.indexOf(registration));
    }

    @Override
    public boolean removeChangeListener(
            CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        return removeAt(ListListenerRegistration.indexOf(listeners, listener));
    }

    private boolean removeAt(int index) {
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
            var view = new ObservableModelList<>(VList.newInstance(Collections.unmodifiableList(observable)),
                    ordinary, modelElements);
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
    @Override public boolean contains(Object value) { return observable.contains(lookupValue(value)); }
    @Override public boolean containsAll(Collection<?> values) {
        return modelElements ? ReferenceAliasList.containsAll(observable, values) : observable.containsAll(values);
    }
    @Override public int indexOf(Object value) { return observable.indexOf(lookupValue(value)); }
    @Override public int lastIndexOf(Object value) { return observable.lastIndexOf(lookupValue(value)); }
    @Override public Object[] toArray() { return observable.toArray(); }
    @Override public <U> U[] toArray(U[] result) { return observable.toArray(result); }
    @Override public boolean remove(Object value) { return observable.remove(lookupValue(value)); }
    @Override public T remove(int index) { return observable.remove(index); }
    @Override public boolean removeAll(int... indices) { return observable.removeAll(indices); }
    @Override public boolean removeAll(Collection<?> values) { return observable.removeAll(values); }
    @Override public boolean retainAll(Collection<?> values) { return observable.retainAll(values); }
    @Override public boolean removeIf(Predicate<? super T> predicate) { return observable.removeIf(predicate); }
    @Override public void replaceAll(UnaryOperator<T> operator) {
        if (unmodifiable == this) throw new UnsupportedOperationException("Read-only list.");
        Objects.requireNonNull(operator);
        if (observable.isEmpty()) return;

        // The raw VList applies the operator in place. A later exception otherwise
        // leaves an unrecorded mutation with no change event.
        var before = new ArrayList<>(observable);
        var after = new ArrayList<>(before);
        after.replaceAll(operator);
        if (observable.size() != before.size()) throw new ConcurrentModificationException();
        for (int index = 0; index < before.size(); index++) {
            if (observable.get(index) != before.get(index)) throw new ConcurrentModificationException();
        }
        observable.setAll(0, after);
    }
    @Override public void sort(Comparator<? super T> comparator) { observable.sort(comparator); }
    @Override public void clear() { observable.clear(); }
    @Override public Iterator<T> iterator() { return observable.iterator(); }
    @Override public ListIterator<T> listIterator() { return observable.listIterator(); }
    @Override public ListIterator<T> listIterator(int index) { return observable.listIterator(index); }
    @Override public List<T> subList(int from, int to) {
        var range = observable.subList(from, to);
        return modelElements ? new ReferenceAliasList<>(range) : range;
    }
    @Override public String getEventInfo() { return observable.getEventInfo(); }
    @Override public void setEventInfo(String value) { observable.setEventInfo(value); }
    @Override public boolean equals(Object other) { return other == this || observable.equals(other); }
    @Override public int hashCode() { return observable.hashCode(); }

    private Object lookupValue(Object value) {
        return modelElements ? ReferenceCollections.identity(value) : value;
    }

    static final class ModelListEvent<T> implements VListChangeEvent<T> {
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
