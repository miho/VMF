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
import eu.mihosoft.vcollections.VSet;
import java.util.*;
import java.util.function.UnaryOperator;

/** Generated-model storage for constrained scalar collection properties. */
public final class ManagedValues {
    private ManagedValues() { }

    public static <E> VList<E> list(Class<E> type, int upper, boolean unique) {
        return new CheckedList<>(type, upper, unique);
    }

    static boolean isOrderedSet(List<?> list) {
        return list instanceof CheckedList && ((CheckedList<?>) list).unique;
    }

    /** Replace or append only after checking the entire proposed value. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void copy(Collection<?> target, Collection<?> source, boolean append) {
        Objects.requireNonNull(source, "collection property");
        if (target instanceof VSet) {
            if (append) ((VSet) target).addAll(source);
            else ((VSet) target).replaceContents(source);
        } else {
            CheckedList list = (CheckedList) target;
            if (append) list.addAll(source);
            else list.replace(source);
        }
    }

    public static Collection<?> snapshot(Collection<?> values, Class<?> type, int upper, boolean set, boolean unique) {
        Collection<?> target = set ? VSet.checked(type, upper) : list(type, upper, unique);
        copy(target, values, false);
        return set ? Collections.unmodifiableSet(new LinkedHashSet<>(target)) : Collections.unmodifiableList(new ArrayList<>(target));
    }

    private static final class CheckedList<E> extends ObservableModelList<E> {
        private final Class<E> type;
        private final int upper;
        private final boolean unique;
        private int revision;
        CheckedList(Class<E> type, int upper, boolean unique) {
            super(VList.newInstance(new ArrayList<>()));
            this.type = type;
            this.upper = upper;
            this.unique = unique;
        }

        @Override public VList<E> asUnmodifiable() {
            return unmodifiableView(true);
        }

        private void check(List<E> next) {
            checkSize(next.size());
            Set<E> seen = unique ? new HashSet<>() : null;
            for (E value : next) {
                type.cast(Objects.requireNonNull(value, "list member"));
                if (seen != null && !seen.add(value))
                    throw new IllegalArgumentException("Duplicate member in unique list: " + value);
            }
        }

        private boolean insert(int index, E value, boolean ignoreDuplicate) {
            Objects.checkIndex(index, size() + 1);
            type.cast(Objects.requireNonNull(value, "list member"));
            if (unique && contains(value)) {
                if (ignoreDuplicate) return false;
                throw new IllegalArgumentException("Duplicate member in ordered set: " + value);
            }
            checkSize((long) size() + 1);
            revision++;
            super.add(index, value);
            return true;
        }

        private void checkSize(long count) {
            if (upper != -1 && count > upper) throw new IllegalArgumentException("List exceeds upper bound " + upper);
        }

        @Override public void add(int index, E value) { insert(index, value, false); }
        @Override public boolean add(E value) { return insert(size(), value, true); }
        @Override public boolean addAll(Collection<? extends E> entries) { return addAll(size(), entries); }
        @Override public boolean addAll(int index, Collection<? extends E> entries) {
            Objects.checkIndex(index, size() + 1);
            List<E> added = new ArrayList<>();
            Set<E> seen = unique ? new HashSet<>(this) : null;
            for (E entry : entries) {
                type.cast(Objects.requireNonNull(entry, "list member"));
                if (seen == null || seen.add(entry)) added.add(entry);
            }
            if (added.isEmpty()) return false;
            checkSize((long) size() + added.size());
            revision++;
            return super.addAll(index, added);
        }

        @Override public boolean addAll(int[] indices, Collection<? extends E> entries) {
            List<E> added = new ArrayList<>(entries);
            List<E> next = new ArrayList<>(this);
            next.addAll(added);
            check(next);
            if (indices.length != added.size()) throw new IllegalArgumentException("Index and value counts differ");
            if (added.isEmpty()) return false;
            int previous = size();
            try {
                return super.addAll(indices, added);
            } finally {
                if (size() != previous) revision++;
            }
        }

        @Override public E set(int index, E value) {
            E previous = get(index);
            setAll(index, Collections.singletonList(value));
            return previous;
        }
        @Override public Collection<E> setAll(int index, Collection<E> entries) {
            List<E> updates = new ArrayList<>(entries);
            List<E> next = new ArrayList<>(this);
            Objects.checkFromIndexSize(index, updates.size(), size());
            for (int i = 0; i < updates.size(); i++) next.set(index + i, updates.get(i));
            check(next);
            return super.setAll(index, updates);
        }

        void replace(Collection<? extends E> entries) {
            List<E> next = new ArrayList<>(entries);
            check(next);
            if (equals(next)) return;
            if (size() == next.size()) { setAll(0, next); return; }
            Throwable failure = null;
            try { clear(); } catch (RuntimeException | Error ex) { failure = ex; }
            try {
                if (unique && !isEmpty() && !Collections.disjoint(this, next)) {
                    throw new IllegalArgumentException("Duplicate member introduced during ordered-set replacement");
                }
                addAll(next);
            } catch (RuntimeException | Error ex) { failure = ObserverFailures.add(failure, ex); }
            ObserverFailures.rethrow(failure);
        }

        @Override public void replaceAll(UnaryOperator<E> operator) {
            List<E> next = new ArrayList<>(this);
            next.replaceAll(operator);
            setAll(0, next);
        }
        @Override public void sort(Comparator<? super E> comparator) {
            List<E> next = new ArrayList<>(this);
            next.sort(comparator);
            if (!equals(next)) setAll(0, next);
        }

        @Override public E remove(int index) {
            Objects.checkIndex(index, size());
            revision++;
            return super.remove(index);
        }
        @Override public boolean remove(Object value) {
            int index = indexOf(value);
            if (index < 0) return false;
            remove(index);
            return true;
        }
        @Override public boolean removeAll(int... indices) {
            int[] sorted = indices.clone();
            Arrays.sort(sorted);
            for (int i = 0; i < sorted.length; i++) {
                Objects.checkIndex(sorted[i], size());
                if (i > 0 && sorted[i] == sorted[i - 1]) throw new IllegalArgumentException("Duplicate index");
            }
            if (sorted.length == 0) return false;
            revision++;
            return super.removeAll(sorted);
        }
        @Override public boolean removeIf(java.util.function.Predicate<? super E> predicate) {
            Objects.requireNonNull(predicate);
            return removeAll(java.util.stream.IntStream.range(0, size()).filter(i -> predicate.test(get(i))).toArray());
        }
        @Override public boolean removeAll(Collection<?> values) { return removeIf(values::contains); }
        @Override public boolean retainAll(Collection<?> values) { return removeIf(value -> !values.contains(value)); }
        @Override public void clear() { removeAll(java.util.stream.IntStream.range(0, size()).toArray()); }

        @Override public Iterator<E> iterator() { return listIterator(); }
        @Override public ListIterator<E> listIterator() { return listIterator(0); }
        @Override public ListIterator<E> listIterator(int start) { return subList(0, size()).listIterator(start); }

        @Override public List<E> subList(int from, int to) {
            Objects.checkFromToIndex(from, to, size());
            return new Range(from, to, null);
        }

        /** All iterator/sublist mutations re-enter the owner validation, including bulk replacement. */
        private final class Range extends AbstractList<E> {
            private final int offset;
            private int length;
            private int expectedRevision;
            private final Range parent;
            Range(int from, int to, Range parent) {
                offset = from;
                length = to - from;
                expectedRevision = revision;
                this.parent = parent;
            }
            private void current() {
                if (expectedRevision != revision) throw new ConcurrentModificationException();
            }
            private void resized(int previous) {
                if (revision == expectedRevision) return;
                int change = CheckedList.this.size() - previous;
                for (Range range = this; range != null; range = range.parent) {
                    range.length += change;
                    range.expectedRevision = revision;
                    range.modCount++;
                }
            }
            @Override public List<E> subList(int from, int to) {
                Objects.checkFromToIndex(from, to, size());
                return new Range(offset + from, offset + to, this);
            }
            @Override public int size() { current(); return length; }
            @Override public E get(int i) { Objects.checkIndex(i, size()); return CheckedList.this.get(offset + i); }
            @Override public E set(int i, E value) { Objects.checkIndex(i, size()); return CheckedList.this.set(offset + i, value); }
            @Override public boolean add(E value) {
                return addAll(size(), Collections.singletonList(value));
            }
            @Override public void add(int i, E value) {
                Objects.checkIndex(i, size() + 1);
                int previous = CheckedList.this.size();
                try { CheckedList.this.add(offset + i, value); } finally { resized(previous); }
            }
            @Override public boolean addAll(Collection<? extends E> v) { return addAll(size(), v); }
            @Override public boolean addAll(int i, Collection<? extends E> v) {
                Objects.checkIndex(i, size() + 1);
                int previous = CheckedList.this.size();
                try { return CheckedList.this.addAll(offset + i, v); } finally { resized(previous); }
            }
            @Override public E remove(int i) {
                Objects.checkIndex(i, size());
                int previous = CheckedList.this.size();
                try { return CheckedList.this.remove(offset + i); } finally { resized(previous); }
            }
            @Override public void replaceAll(UnaryOperator<E> operator) {
                List<E> next = new ArrayList<>(this);
                next.replaceAll(operator);
                current();
                CheckedList.this.setAll(offset, next);
            }
            @Override public void sort(Comparator<? super E> comparator) {
                List<E> next = new ArrayList<>(this);
                next.sort(comparator);
                current();
                CheckedList.this.setAll(offset, next);
            }
        }
    }
}
