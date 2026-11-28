/*
 * Copyright 2017-2024 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vmf.runtime.core.VObject;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Orders the deliveries of change events (portable contract E8 and H4).
 *
 * <p><b>Nearest observer first (E8).</b> Each dispatcher that {@link ChangesImpl} relays listen to, the property
 * support of an object or one of its collections, has a list of the observers' relay handlers, nearest observer
 * first: the object's own changes, then its container's, and so on. The relays registered with the dispatcher are
 * interchangeable slots: the k-th slot that a dispatch calls runs the k-th handler of that dispatch. Observers that
 * start in any order therefore register like before, by appending, and only their handler is inserted in place.
 * Each slot still runs exactly one handler, so a failing handler is reported like a failing relay. A dispatcher
 * whose relays all belong to one observer needs no order between observers: each slot runs its own handler, which
 * also serves read-only views, whose relays each receive their own copy of an event.</p>
 *
 * <p>A dispatcher of VMF's own, a model object or one of its lists, keeps this list itself, and only once a second
 * handler arrives: until then its only handler is the whole order, so that observing a model costs no lookup, lock
 * or list per object. Other dispatchers (the collections of VCollections, read-only views, model classes generated
 * by earlier versions) are looked up by identity.</p>
 *
 * <p><b>Recorded after the cause (H4).</b> While a recording is active, an event is stamped when the first relay
 * handler receives it, before any listener can react to it. A recording orders its history by these stamps, so an
 * edit made by a listener follows the edit whose delivery triggered it, even if the recording receives the
 * triggering event last. VMF's own events keep their stamp themselves; other events are looked up by identity.</p>
 */
@SuppressWarnings("deprecation")
final class DeliveryOrder {
    /** What {@link VObjectInternal#_vmf_getDeliveryOrder()} returns for a dispatcher that is looked up by identity. */
    static final Object EXTERNAL = new Object();

    private static final AtomicLong NEXT_STAMP = new AtomicLong(1);
    private static final Map<IdentityKey, Long> STAMPS = new HashMap<>();
    private static final ReferenceQueue<Object> EXPIRED_STAMPS = new ReferenceQueue<>();
    private static volatile int activeRecordings;
    // Stamps below this one were given before the last recording stopped and are forgotten.
    private static volatile long stampFloor = 1;
    private static WeakReference<Object> lastEvent = new WeakReference<>(null);
    private static long lastStamp;

    /** An event that keeps its delivery stamp itself. */
    interface Stamped {
        long deliveryStamp();

        void deliveryStamp(long stamp);
    }

    // Each dispatcher's relay handlers, held weakly: the relays registered with the dispatcher keep them, so they
    // live as long as the dispatcher has relays. Holding them strongly would keep every observed object alive,
    // because the handlers reach the observers' models and those reach the dispatcher.
    private static final Map<IdentityKey, WeakReference<Relays>> RELAYS = new HashMap<>();
    private static final ReferenceQueue<Object> EXPIRED_DISPATCHERS = new ReferenceQueue<>();

    private DeliveryOrder() {
    }

    // ------------------------------------------------------------------------------------------------- stamps (H4)

    /** Stamps are only needed while some recording is active. */
    static synchronized void recordingChanged(boolean started) {
        int active = activeRecordings + (started ? 1 : -1);
        if (active <= 0) {
            active = 0;
            STAMPS.clear();
            lastEvent = new WeakReference<>(null);
            stampFloor = NEXT_STAMP.get();
        }
        activeRecordings = active;
    }

    /** The stamp of an event; the first call for an event assigns the next stamp. 0 while nothing records. */
    static long stamp(Object event) {
        if (event == null || activeRecordings == 0) return 0;
        if (event instanceof Stamped) {
            var stamped = (Stamped) event;
            long stamp = stamped.deliveryStamp();
            if (stamp < stampFloor) {
                stamp = NEXT_STAMP.getAndIncrement();
                stamped.deliveryStamp(stamp);
            }
            return stamp;
        }
        return stampByIdentity(event);
    }

    private static synchronized long stampByIdentity(Object event) {
        if (activeRecordings == 0) return 0;
        // The relays of one event usually follow each other.
        if (lastEvent.get() == event) return lastStamp;
        purge(STAMPS, EXPIRED_STAMPS);
        long stamp = STAMPS.computeIfAbsent(new IdentityKey(event, EXPIRED_STAMPS), key -> NEXT_STAMP.getAndIncrement());
        lastEvent = new WeakReference<>(event);
        lastStamp = stamp;
        return stamp;
    }

    // ------------------------------------------------------------------------------------------- relay order (E8)

    /**
     * The work one observer's relay does for the events of one dispatcher; its slot keeps it. A relay can be its own
     * handler.
     */
    static class Handler {
        final ChangesImpl observer;
        final VObject observerModel;
        final boolean recursive;
        final Consumer<Object> body;
        // The dispatcher if it keeps its order itself: a model object or one of VMF's lists.
        private VObjectInternal object;
        private ContainmentList<?> containment;
        private ObservableModelList<?> observable;
        // The order this handler shares with the other handlers of its dispatcher; null while it is the only one.
        private volatile Relays relays;
        // Whether the dispatcher prepared a dispatch while this was its only handler.
        private boolean prepared;

        Handler(ChangesImpl observer, VObject observerModel, boolean recursive, Consumer<Object> body) {
            this.observer = observer;
            this.observerModel = observerModel;
            this.recursive = recursive;
            this.body = body;
        }

        private Object order() {
            if (object != null) return object._vmf_getDeliveryOrder();
            if (containment != null) return containment.deliveryOrder;
            return observable != null ? observable.deliveryOrder : EXTERNAL;
        }

        private void order(Object order) {
            if (object != null) object._vmf_setDeliveryOrder(order);
            else if (containment != null) containment.deliveryOrder = order;
            else observable.deliveryOrder = order;
        }
    }

    /**
     * Where an observer's handlers go in a dispatcher's list: after the handlers of observers nearer to the object
     * and of the observer itself, before those of observers farther away. One position serves all objects of a
     * subtree that the observer starts to observe at once.
     */
    static final class Position {
        private final VObject model;
        private Set<Object> ancestors;

        Position(VObject model) {
            this.model = model;
        }

        /** Whether another observer is farther away: its model contains this observer's model. */
        boolean isFarther(VObject other) {
            if (other == model || other == null) return false;
            if (ancestors == null) {
                ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
                for (VObject current = containerOf(model); current != null; current = containerOf(current)) {
                    ancestors.add(current);
                }
            }
            return ancestors.contains(other);
        }
    }

    private static VObject containerOf(VObject object) {
        return object instanceof VObjectInternalModifiable ? ((VObjectInternal) object)._vmf_getContainer() : null;
    }

    /**
     * Adds an observer's relay handler to the handlers of a model object's property changes, nearest observers
     * first. The relay registered with the object calls {@link #prepare} and {@link #deliver} with the handler.
     */
    static void attach(VObjectInternal object, Handler handler, Position position) {
        Object order = object._vmf_getDeliveryOrder();
        if (order == EXTERNAL) {
            attachByIdentity(object, handler, position);
        } else {
            handler.object = object;
            attach(handler, order, position);
        }
    }

    /** Adds an observer's relay handler to the handlers of a collection's changes, nearest observers first. */
    static void attach(Object collection, Handler handler, Position position) {
        if (collection instanceof ContainmentList) {
            handler.containment = (ContainmentList<?>) collection;
            attach(handler, handler.containment.deliveryOrder, position);
        } else if (collection instanceof ObservableModelList) {
            handler.observable = (ObservableModelList<?>) collection;
            attach(handler, handler.observable.deliveryOrder, position);
        } else {
            attachByIdentity(collection, handler, position);
        }
    }

    private static void attach(Handler handler, Object order, Position position) {
        if (order == null) {
            // The only handler of the dispatcher: its relay runs it, and nothing else is kept.
            handler.order(handler);
            return;
        }
        Relays relays;
        if (order instanceof Relays) {
            relays = (Relays) order;
        } else {
            var only = (Handler) order;
            relays = new Relays(only);
            only.relays = relays;
            handler.order(relays);
        }
        handler.relays = relays;
        relays.insert(handler, position);
    }

    private static void attachByIdentity(Object dispatcher, Handler handler, Position position) {
        Relays relays;
        synchronized (DeliveryOrder.class) {
            purge(RELAYS, EXPIRED_DISPATCHERS);
            var key = new IdentityKey(dispatcher, EXPIRED_DISPATCHERS);
            var existing = RELAYS.get(key);
            relays = existing == null ? null : existing.get();
            if (relays == null) {
                relays = new Relays();
                RELAYS.put(key, new WeakReference<>(relays));
            }
        }
        handler.relays = relays;
        relays.insert(handler, position);
    }

    /** Removes a relay handler from its dispatcher's list. */
    static void detach(Handler handler) {
        Relays relays = handler.relays;
        if (relays != null) {
            // The dispatcher keeps the list, also when it is empty: dispatches in progress still follow it.
            relays.remove(handler);
        } else if (handler.order() == handler) {
            handler.order(null);
        }
    }

    /** Takes the order of a dispatch before its listeners run; called from a relay's prepare phase. */
    static void prepare(Handler handler, Object event) {
        Relays relays = handler.relays;
        if (relays == null) handler.prepared = true;
        else relays.prepare(event);
    }

    /**
     * Called by a relay: runs the next handler of this dispatch, nearest observers first, or the relay's own handler
     * where only one observer relays from the dispatcher.
     */
    static void deliver(Handler handler, Object event) {
        Relays relays = handler.relays;
        Handler next = relays == null ? handler : relays.next(event, handler);
        if (next != null) next.body.accept(event);
    }


    /** The relay handlers of one dispatcher, nearest observer first, and the dispatches in progress. */
    private static final class Relays {
        private static final Handler[] NONE = new Handler[0];
        private Handler[] handlers = NONE;
        // Whether all handlers belong to one observer, or the dispatcher belongs to a read-only view, which hands
        // each relay its own copy of an event: then each relay runs its own handler.
        private boolean oneObserver = true;
        private boolean readOnlyView;
        // Whether the dispatcher has a prepare phase, which takes the order of each dispatch before its listeners.
        private volatile boolean prepared;
        private final ArrayDeque<Dispatch> dispatches = new ArrayDeque<>();
        // Whether every relay runs its own handler and no dispatch is known: then a dispatch needs no bookkeeping.
        private volatile boolean direct = true;

        Relays() {
        }

        /** The list of a dispatcher that had one handler so far. */
        Relays(Handler only) {
            handlers = new Handler[]{only};
            readOnlyView = isReadOnlyView(only);
            prepared = only.prepared;
        }

        private static boolean isReadOnlyView(Handler handler) {
            return handler.observerModel instanceof VObjectInternal
                    && ((VObjectInternal) handler.observerModel)._vmf_isReadOnly();
        }

        private void directChanged() {
            direct = (oneObserver || readOnlyView) && dispatches.isEmpty();
        }

        synchronized void insert(Handler handler, Position position) {
            // The handlers are ordered by distance, so the farther ones form the tail: find where it starts.
            int index = 0;
            int end = handlers.length;
            while (index < end) {
                int middle = (index + end) >>> 1;
                if (position.isFarther(handlers[middle].observerModel)) end = middle;
                else index = middle + 1;
            }
            var updated = new Handler[handlers.length + 1];
            System.arraycopy(handlers, 0, updated, 0, index);
            updated[index] = handler;
            System.arraycopy(handlers, index, updated, index + 1, handlers.length - index);
            oneObserver = oneObserver && (handlers.length == 0 || handlers[0].observer == handler.observer);
            readOnlyView = readOnlyView || isReadOnlyView(handler);
            handlers = updated;
            directChanged();
        }

        synchronized void remove(Handler handler) {
            for (int index = 0; index < handlers.length; index++) {
                if (handlers[index] == handler) {
                    removeAt(index);
                    return;
                }
            }
        }

        private void removeAt(int index) {
            if (handlers.length == 1) {
                handlers = NONE;
                oneObserver = true;
                directChanged();
                return;
            }
            var updated = new Handler[handlers.length - 1];
            System.arraycopy(handlers, 0, updated, 0, index);
            System.arraycopy(handlers, index + 1, updated, index, handlers.length - index - 1);
            handlers = updated;
            oneObserver = true;
            for (Handler handler : updated) oneObserver = oneObserver && handler.observer == updated[0].observer;
            directChanged();
        }

        private Dispatch find(Object event) {
            for (Dispatch dispatch : dispatches) {
                if (dispatch.event.get() == event) return dispatch;
            }
            return null;
        }

        /** The dispatch of an event, taking the handlers in their current order when it starts. */
        Dispatch dispatch(Object event) {
            var dispatch = find(event);
            if (dispatch != null) return dispatch;
            forgetFinished();
            dispatch = new Dispatch(event, handlers);
            dispatches.addFirst(dispatch);
            direct = false;
            return dispatch;
        }

        void prepare(Object event) {
            if (direct && prepared) return;
            synchronized (this) {
                prepared = true;
                if (!oneObserver && !readOnlyView) dispatch(event);
            }
        }

        /**
         * The handler a relay runs. A dispatch orders the observers if several of them relay from the dispatcher when
         * it starts: at its prepare phase, or at its first relay where the dispatcher has none. Otherwise each relay
         * runs its own handler, also if another observer starts to relay during the dispatch.
         */
        Handler next(Object event, Handler own) {
            if (direct) return own;
            synchronized (this) {
                var dispatch = find(event);
                if (dispatch == null) {
                    if (oneObserver || readOnlyView || prepared) return own;
                    dispatch = dispatch(event);
                }
                if (dispatch.cursor >= dispatch.plan.length) return null;
                Handler next = dispatch.plan[dispatch.cursor++];
                // A finished dispatch is only remembered: it must not keep the handlers of observers that left.
                if (dispatch.cursor == dispatch.plan.length) {
                    dispatch.plan = NONE;
                    dispatch.cursor = 0;
                }
                return next;
            }
        }

        /**
         * Forgets dispatches whose event is gone and, when many accumulate, finished ones. A finished dispatch is
         * kept while its event lives, so that a relay removed during the dispatch cannot start it again.
         */
        private void forgetFinished() {
            if (dispatches.size() < 4) return;
            dispatches.removeIf(dispatch -> dispatch.event.get() == null);
            for (Iterator<Dispatch> iterator = dispatches.descendingIterator(); iterator.hasNext();) {
                if (dispatches.size() < 4) break;
                Dispatch dispatch = iterator.next();
                if (dispatch.cursor >= dispatch.plan.length) iterator.remove();
            }
            directChanged();
        }
    }

    private static final class Dispatch {
        final WeakReference<Object> event;
        // The handlers in the order of this dispatch; none once all of them ran.
        Handler[] plan;
        int cursor;

        Dispatch(Object event, Handler[] plan) {
            this.event = new WeakReference<>(event);
            this.plan = plan;
        }
    }

    // ----------------------------------------------------------------------------------------------------- support

    private static void purge(Map<IdentityKey, ?> map, ReferenceQueue<Object> queue) {
        Object expired;
        while ((expired = queue.poll()) != null) {
            map.remove(expired);
        }
    }

    private static final class IdentityKey extends WeakReference<Object> {
        private final int hash;

        IdentityKey(Object value, ReferenceQueue<Object> queue) {
            super(value, queue);
            hash = System.identityHashCode(value);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityKey)) return false;
            Object value = get();
            return value != null && value == ((IdentityKey) other).get();
        }
    }
}
