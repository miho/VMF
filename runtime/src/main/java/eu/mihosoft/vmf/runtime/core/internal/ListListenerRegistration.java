package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vcollections.VListChangeEvent;
import vjavax.observer.collection.CollectionChangeEvent;
import vjavax.observer.collection.CollectionChangeListener;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Keeps the identity of a subscription separate from the callback it invokes. */
final class ListListenerRegistration<T> {
    private final CollectionChangeListener<T, VList<T>, VListChange<T>> listener;
    private final Consumer<VListChangeEvent<T>> prepare;

    ListListenerRegistration(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        this(listener, null);
    }

    @SuppressWarnings("unchecked")
    ListListenerRegistration(CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener,
                             Consumer<VListChangeEvent<T>> prepare) {
        this.listener = (CollectionChangeListener<T, VList<T>, VListChange<T>>) listener;
        this.prepare = prepare;
    }

    void onChange(CollectionChangeEvent<T, VList<T>, VListChange<T>> event) {
        listener.onChange(event);
    }

    void prepare(VListChangeEvent<T> event) {
        if (prepare != null) prepare.accept(event);
    }

    static <T> int indexOf(List<ListListenerRegistration<T>> registrations,
                          CollectionChangeListener<T, ? super VList<T>, ? super VListChange<T>> listener) {
        for (int index = 0; index < registrations.size(); index++) {
            if (Objects.equals(listener, registrations.get(index).listener)) return index;
        }
        return -1;
    }
}
