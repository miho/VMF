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
 * <p><b>Recorded after the cause (H4).</b> While a recording is active, an event is stamped when the first relay
 * handler receives it, before any listener can react to it. A recording orders its history by these stamps, so an
 * edit made by a listener follows the edit whose delivery triggered it, even if the recording receives the
 * triggering event last.</p>
 */
@SuppressWarnings("deprecation")
final class DeliveryOrder {
    private static long nextStamp = 1;
    private static final Map<IdentityKey, Long> STAMPS = new HashMap<>();
    private static final ReferenceQueue<Object> EXPIRED_STAMPS = new ReferenceQueue<>();
    private static int activeRecordings;
    private static WeakReference<Object> lastEvent = new WeakReference<>(null);
    private static long lastStamp;

    private static final Map<IdentityKey, Relays> RELAYS = new HashMap<>();
    private static final ReferenceQueue<Object> EXPIRED_DISPATCHERS = new ReferenceQueue<>();

    private DeliveryOrder() {
    }

    // ------------------------------------------------------------------------------------------------- stamps (H4)

    /** Stamps are only needed while some recording is active. */
    static synchronized void recordingChanged(boolean started) {
        activeRecordings += started ? 1 : -1;
        if (activeRecordings <= 0) {
            activeRecordings = 0;
            STAMPS.clear();
            lastEvent = new WeakReference<>(null);
        }
    }

    /** The stamp of an event; the first call for an event assigns the next stamp. 0 while nothing records. */
    static synchronized long stamp(Object event) {
        if (event == null || activeRecordings == 0) return 0;
        // The relays of one event usually follow each other.
        if (lastEvent.get() == event) return lastStamp;
        purge(STAMPS, EXPIRED_STAMPS);
        long stamp = STAMPS.computeIfAbsent(new IdentityKey(event, EXPIRED_STAMPS), key -> nextStamp++);
        lastEvent = new WeakReference<>(event);
        lastStamp = stamp;
        return stamp;
    }

    // ------------------------------------------------------------------------------------------- relay order (E8)

    /** The work one observer's relay does for the events of one dispatcher; its slot keeps it. */
    static final class Handler {
        final ChangesImpl observer;
        final VObject observerModel;
        final boolean recursive;
        final Consumer<Object> body;
        private final Relays relays;

        private Handler(ChangesImpl observer, VObject observerModel, boolean recursive, Consumer<Object> body,
                        Relays relays) {
            this.observer = observer;
            this.observerModel = observerModel;
            this.recursive = recursive;
            this.body = body;
            this.relays = relays;
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
     * Adds an observer's relay handler to a dispatcher's list, nearest observers first. The relay registered with the
     * dispatcher keeps the handler and calls {@link #prepare} and {@link #deliver} with it.
     */
    static Handler attach(Object dispatcher, ChangesImpl observer, VObject observerModel, boolean recursive,
                          Consumer<Object> body, Position position) {
        Relays relays;
        synchronized (DeliveryOrder.class) {
            purge(RELAYS, EXPIRED_DISPATCHERS);
            relays = RELAYS.computeIfAbsent(new IdentityKey(dispatcher, EXPIRED_DISPATCHERS), key -> new Relays());
        }
        var handler = new Handler(observer, observerModel, recursive, body, relays);
        relays.insert(handler, position);
        return handler;
    }

    /** Removes a relay handler from its dispatcher's list. */
    static void detach(Handler handler) {
        handler.relays.remove(handler);
    }

    /** Takes the order of a dispatch before its listeners run; called from a relay's prepare phase. */
    static void prepare(Handler handler, Object event) {
        handler.relays.prepare(event);
    }

    /**
     * Called by a relay: runs the next handler of this dispatch, nearest observers first, or the relay's own handler
     * where only one observer relays from the dispatcher.
     */
    static void deliver(Handler handler, Object event) {
        Handler next = handler.relays.next(event, handler);
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
        private boolean prepared;
        private final ArrayDeque<Dispatch> dispatches = new ArrayDeque<>();

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
            readOnlyView = readOnlyView || handler.observerModel instanceof VObjectInternal
                    && ((VObjectInternal) handler.observerModel)._vmf_isReadOnly();
            handlers = updated;
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
                return;
            }
            var updated = new Handler[handlers.length - 1];
            System.arraycopy(handlers, 0, updated, 0, index);
            System.arraycopy(handlers, index + 1, updated, index, handlers.length - index - 1);
            handlers = updated;
            oneObserver = true;
            for (Handler handler : updated) oneObserver = oneObserver && handler.observer == updated[0].observer;
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
            return dispatch;
        }

        synchronized void prepare(Object event) {
            prepared = true;
            if (!oneObserver && !readOnlyView) dispatch(event);
        }

        /**
         * The handler a relay runs. A dispatch orders the observers if several of them relay from the dispatcher when
         * it starts: at its prepare phase, or at its first relay where the dispatcher has none. Otherwise each relay
         * runs its own handler, also if another observer starts to relay during the dispatch.
         */
        synchronized Handler next(Object event, Handler own) {
            var dispatch = find(event);
            if (dispatch == null) {
                if (oneObserver || readOnlyView || prepared) return own;
                dispatch = dispatch(event);
            }
            return dispatch.cursor < dispatch.plan.length ? dispatch.plan[dispatch.cursor++] : null;
        }

        /**
         * Forgets dispatches whose event is gone and, when many accumulate, finished ones. A finished dispatch is
         * kept while its event lives, so that a relay removed during the dispatch cannot start it again.
         */
        private void forgetFinished() {
            if (dispatches.size() < 4) return;
            dispatches.removeIf(dispatch -> dispatch.event.get() == null);
            for (Iterator<Dispatch> iterator = dispatches.descendingIterator(); iterator.hasNext();) {
                if (dispatches.size() < 4) return;
                Dispatch dispatch = iterator.next();
                if (dispatch.cursor >= dispatch.plan.length) iterator.remove();
            }
        }
    }

    private static final class Dispatch {
        final WeakReference<Object> event;
        // The handlers in the order of this dispatch.
        final Handler[] plan;
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
