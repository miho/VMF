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
import eu.mihosoft.vmf.runtime.core.OrderedRefSet;
import eu.mihosoft.vmf.runtime.core.RefSet;
import eu.mihosoft.vmf.runtime.core.VObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/** Identity-based reference storage and views used by generated model code. */
public final class ReferenceCollections {
    private ReferenceCollections() { }

    public static <E> VList<E> checked(VList<E> storage, Class<E> type, boolean ordered) {
        return new ReferenceList<>(storage, type, ordered);
    }

    public static boolean isReferenceList(Object list) { return list instanceof ReferenceList; }

    public static <E extends VObject> RefSet<E> unordered(VList<E> storage) {
        return new View<>(storage, Function.identity(), storage);
    }

    public static <E extends VObject> OrderedRefSet<E> ordered(VList<E> storage) {
        return new OrderedView<>(storage, Function.identity(), storage);
    }

    @SuppressWarnings("unchecked")
    public static <E extends VObject, R extends VObject> RefSet<R> readOnly(RefSet<E> references, Function<? super E, ? extends R> project) {
        View<?, E> view = (View<?, E>) references;
        return view.project(project, references instanceof OrderedRefSet);
    }

    @SuppressWarnings("unchecked")
    public static <E extends VObject, R extends VObject> OrderedRefSet<R> readOnly(OrderedRefSet<E> references, Function<? super E, ? extends R> project) {
        View<?, E> view = (View<?, E>) references;
        return (OrderedRefSet<R>) view.project(project, true);
    }

    /** Checks every candidate before a builder or reflective replacement can change any link. */
    public static <E> List<E> snapshot(Iterable<? extends E> source, Class<E> type) {
        Set<E> seen = identitySet();
        List<E> result = new ArrayList<>();
        for (E value : source) {
            type.cast(Objects.requireNonNull(value, "reference target"));
            requireWritableTarget(value);
            if (!seen.add(value)) throw new IllegalArgumentException("Duplicate model reference");
            result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    public static <E> void copy(VList<E> target, Iterable<? extends E> source, boolean append) {
        ReferenceList<E> references = (ReferenceList<E>) target;
        Collection<? extends E> values = asCollection(source);
        if (append) references.addAll(values);
        else references.replace(values);
    }

    @SuppressWarnings("unchecked")
    private static <E> Collection<? extends E> asCollection(Iterable<? extends E> values) {
        if (values instanceof Collection) return (Collection<? extends E>) values;
        ArrayList<E> snapshot = values instanceof RefSet ? new ArrayList<>(((RefSet<?>) values).size()) : new ArrayList<>();
        values.forEach(snapshot::add);
        return snapshot;
    }

    static Object identity(Object value) {
        if (value instanceof VObjectInternal && ((VObjectInternal) value)._vmf_isReadOnly())
            return ((VObjectInternal) value)._vmf_getMutableObject();
        return value;
    }

    private static void requireWritableTarget(Object target) {
        if (!(target instanceof VObjectInternal))
            throw new IllegalArgumentException("A reference target must be a VMF model object");
        if (((VObjectInternal) target)._vmf_isReadOnly())
            throw new IllegalArgumentException("A read-only view cannot be stored as a writable reference");
    }

    private static <E> Set<E> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Compares reference targets across mutable and read-only views. */
    public static boolean sameMembers(Iterable<?> first, Iterable<?> second, boolean ordered) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        if (ordered) {
            Iterator<?> a = first.iterator(), b = second.iterator();
            while (a.hasNext() && b.hasNext()) {
                if (identity(a.next()) != identity(b.next())) return false;
            }
            return !a.hasNext() && !b.hasNext();
        }
        Set<Object> remaining = identitySet();
        for (Object value : first) if (!remaining.add(identity(value))) return false;
        for (Object value : second) if (!remaining.remove(identity(value))) return false;
        return remaining.isEmpty();
    }

    /** Captures the targets in one change so bulk opposite maintenance can test overlap in linear time. */
    public static Predicate<Object> membership(List<?> values) {
        if (values.isEmpty()) return ignored -> false;
        if (values.size() == 1) {
            Object single = identity(values.get(0));
            return value -> identity(value) == single;
        }
        Set<Object> members = identitySet();
        for (Object member : values) members.add(identity(member));
        return value -> members.contains(identity(value));
    }

    private static int position(List<?> values, Object target) {
        Object needle = identity(target);
        for (int i = 0; i < values.size(); i++) if (identity(values.get(i)) == needle) return i;
        return -1;
    }

    static VList<?> removingForContainment(VList<?> list) {
        if (!(list instanceof ReferenceList)) return list;
        ReferenceList<?> references = (ReferenceList<?>) list;
        references.invalidateIterators();
        return references.storage;
    }

    static ContainmentList<?> containment(Object list) {
        if (list instanceof ReferenceList) list = ((ReferenceList<?>) list).storage;
        return list instanceof ContainmentList ? (ContainmentList<?>) list : null;
    }

    /** CheckedList owns the indexed edits and range views, while storage maintains the actual graph links. */
    private static final class ReferenceList<E> extends ManagedValues.CheckedList<E> {
        final VList<E> storage;
        final boolean ordered;

        ReferenceList(VList<E> storage, Class<E> type, boolean ordered) {
            super(type, -1, true, storage);
            this.storage = storage;
            this.ordered = ordered;
            check(this);
        }

        @Override void checkElement(E value) {
            super.checkElement(value);
            requireWritableTarget(value);
        }
        @Override Set<E> newMembershipSet() { return identitySet(); }
        @Override boolean sameSequence(List<E> other) { return sameMembers(this, other, true); }
        @Override public E set(int index, E value) {
            E current = get(index);
            if (current == value) return current;
            return super.set(index, value);
        }

        @Override public int indexOf(Object value) { return position(this, value); }
        @Override public int lastIndexOf(Object value) { return indexOf(value); }
        @Override public boolean contains(Object value) { return indexOf(value) >= 0; }
        @Override public boolean containsAll(Collection<?> values) {
            for (Object value : values) if (!contains(value)) return false;
            return true;
        }
        @Override public boolean removeAll(Collection<?> values) { return filterByIdentity(values, false); }
        @Override public boolean retainAll(Collection<?> values) { return filterByIdentity(values, true); }
        private boolean filterByIdentity(Collection<?> values, boolean retain) {
            Set<Object> candidates = identitySet();
            for (Object value : values) candidates.add(identity(value));
            return removeIf(value -> candidates.contains(value) != retain);
        }

        @Override void replace(Collection<? extends E> entries) {
            List<E> next = new ArrayList<>(entries);
            check(next);
            if (sameMembers(this, next, ordered)) return;
            ContainmentList<?> containment = containment(storage);
            if (containment != null) {
                @SuppressWarnings("unchecked") ContainmentList<E> children = (ContainmentList<E>) containment;
                if (children.keys != null) children.keys.validate(children, next);
                try (var preflight = children.attachingAll(next)) { }
            }
            if (ordered && size() == next.size()) {
                setAll(0, next);
                return;
            }

            // Retained edges keep their owners and opposites when a replacement changes size.
            Set<E> wanted = identitySet();
            wanted.addAll(next);
            Throwable failure = null;
            try {
                removeIf(value -> !wanted.contains(value));
            } catch (RuntimeException | Error ex) { failure = ex; }
            try {
                Set<E> present = identitySet();
                present.addAll(this);
                List<E> retained = new ArrayList<>();
                List<E> added = new ArrayList<>();
                List<Integer> insertions = new ArrayList<>();
                for (int i = 0; i < next.size(); i++) {
                    E value = next.get(i);
                    if (present.contains(value)) retained.add(value);
                    else { added.add(value); insertions.add(i); }
                }
                if (ordered && !sameSequence(retained)) {
                    try { setAll(0, retained); }
                    catch (RuntimeException | Error ex) { failure = ObserverFailures.add(failure, ex); }
                }
                if (!added.isEmpty()) {
                    if (ordered) addAll(insertions.stream().mapToInt(Integer::intValue).toArray(), added);
                    else addAll(added);
                }
            } catch (RuntimeException | Error ex) { failure = ObserverFailures.add(failure, ex); }
            ObserverFailures.rethrow(failure);
        }
    }

    private static class View<S, E extends VObject> implements RefSet<E> {
        final VList<S> storage;
        final Function<? super S, ? extends E> reader;
        final VList<E> writable;

        View(VList<S> storage, Function<? super S, ? extends E> reader, VList<E> writable) {
            this.storage = storage;
            this.reader = reader;
            this.writable = writable;
        }

        <R extends VObject> RefSet<R> project(Function<? super E, ? extends R> mapper, boolean ordered) {
            Function<S, R> next = source -> mapper.apply(reader.apply(source));
            return ordered ? new OrderedView<>(storage, next, null) : new View<>(storage, next, null);
        }

        VList<E> edit() {
            if (isReadOnly()) throw new UnsupportedOperationException("Read-only reference collection");
            return writable;
        }
        @Override public boolean isReadOnly() { return writable == null; }
        @Override public int size() { return storage.size(); }
        @Override public boolean contains(Object value) { return position(storage, value) >= 0; }
        @Override public boolean add(E value) { return edit().add(value); }
        @Override public boolean addAll(Iterable<? extends E> values) { return edit().addAll(asCollection(values)); }
        @Override public boolean remove(Object value) { return edit().remove(value); }
        @Override public void clear() { edit().clear(); }

        @Override public Iterator<E> iterator() {
            Iterator<S> source = storage.iterator();
            return new Iterator<E>() {
                @Override public boolean hasNext() { return source.hasNext(); }
                @Override public E next() { return reader.apply(source.next()); }
                @Override public void remove() { edit(); source.remove(); }
            };
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RefSet)) return false;
            boolean ordered = this instanceof OrderedRefSet;
            return ordered == (other instanceof OrderedRefSet) && sameMembers(this, (RefSet<?>) other, ordered);
        }

        @Override public int hashCode() {
            boolean ordered = this instanceof OrderedRefSet;
            int hash = ordered ? 1 : 0;
            for (Object value : storage) {
                int member = System.identityHashCode(identity(value));
                hash = ordered ? 31 * hash + member : hash + member;
            }
            return hash;
        }

        @Override public String toString() { return asCollection(this).toString(); }
    }

    private static final class OrderedView<S, E extends VObject> extends View<S, E> implements OrderedRefSet<E> {
        OrderedView(VList<S> storage, Function<? super S, ? extends E> reader, VList<E> writable) {
            super(storage, reader, writable);
        }
        @Override public E get(int index) { return reader.apply(storage.get(index)); }
        @Override public int indexOf(Object value) { return position(storage, value); }
        @Override public void add(int index, E value) { edit().add(index, value); }
        @Override public E set(int index, E value) { return edit().set(index, value); }
        @Override public E remove(int index) { return edit().remove(index); }
        @Override public void move(int fromIndex, int toIndex) { edit().move(fromIndex, toIndex); }
    }
}
