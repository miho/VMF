package eu.mihosoft.vmf.runtime.core.internal;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Preserves query-side alias lookup on ranges of an observable model list. */
final class ReferenceAliasList<T> extends AbstractList<T> {
    private final List<T> delegate;

    ReferenceAliasList(List<T> delegate) {
        this.delegate = delegate;
    }

    static boolean containsAll(List<?> list, Collection<?> values) {
        for (Object value : values) if (!list.contains(ReferenceCollections.identity(value))) return false;
        return true;
    }

    @Override public boolean contains(Object value) { return delegate.contains(ReferenceCollections.identity(value)); }
    @Override public boolean containsAll(Collection<?> values) { return containsAll(delegate, values); }
    @Override public int indexOf(Object value) { return delegate.indexOf(ReferenceCollections.identity(value)); }
    @Override public int lastIndexOf(Object value) { return delegate.lastIndexOf(ReferenceCollections.identity(value)); }
    @Override public boolean remove(Object value) { return delegate.remove(ReferenceCollections.identity(value)); }

    @Override public T get(int index) { return delegate.get(index); }
    @Override public int size() { return delegate.size(); }
    @Override public T set(int index, T value) { return delegate.set(index, value); }
    @Override public T remove(int index) { return delegate.remove(index); }
    @Override public boolean add(T value) { return delegate.add(value); }
    @Override public void add(int index, T value) { delegate.add(index, value); }
    @Override public boolean addAll(Collection<? extends T> values) { return delegate.addAll(values); }
    @Override public boolean addAll(int index, Collection<? extends T> values) { return delegate.addAll(index, values); }
    @Override public boolean removeAll(Collection<?> values) { return delegate.removeAll(values); }
    @Override public boolean retainAll(Collection<?> values) { return delegate.retainAll(values); }
    @Override public boolean removeIf(Predicate<? super T> predicate) { return delegate.removeIf(predicate); }
    @Override public void replaceAll(UnaryOperator<T> operator) { delegate.replaceAll(operator); }
    @Override public void sort(Comparator<? super T> comparator) { delegate.sort(comparator); }
    @Override public void clear() { delegate.clear(); }
    @Override public Iterator<T> iterator() { return delegate.iterator(); }
    @Override public ListIterator<T> listIterator(int index) { return delegate.listIterator(index); }
    @Override public List<T> subList(int from, int to) { return new ReferenceAliasList<>(delegate.subList(from, to)); }
}
