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
/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 */
package eu.mihosoft.vmf.runtime.core.internal;

import eu.mihosoft.vcollections.VList;
import eu.mihosoft.vcollections.VListChange;
import eu.mihosoft.vcollections.VMappedList;
import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vcollections.VSet;
import eu.mihosoft.vcollections.VListChangeEvent;
import eu.mihosoft.vcollections.VMapChangeEvent;
import eu.mihosoft.vcollections.VSetChangeEvent;
import eu.mihosoft.vmf.runtime.core.*;
import vjavax.observer.Subscription;
import vjavax.observer.collection.CollectionChangeEvent;
import vjavax.observer.collection.CollectionChangeListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
public class ChangesImpl implements Changes {

    private final List<Registration> changeListeners = new CopyOnWriteArrayList<>();
    private final List<Registration> nonRecursiveChangeListeners = new CopyOnWriteArrayList<>();
    // A null index means at least one listener needs every change in that scope.
    private Set<String> recursivePropertyNames = Collections.emptySet();
    private Set<String> localPropertyNames = Collections.emptySet();

    private VList<Change> transactionChanges;

    private final VList<Change> all = VList.newInstance(new ArrayList<>());
    // The delivery stamp of each entry of all(), in the same order (H4).
    private final List<Long> historyStamps = new ArrayList<>();
    private long registrationSequence;
    private final VList<Change> unmodifiableAll = VMappedList.newUnmodifiableInstance(all, Function.identity());
    private final VList<Transaction> transactions
            = VList.newInstance(new ArrayList<>());
    private final VList<Transaction> unmodifiableTransactions =
            VMappedList.newUnmodifiableInstance(transactions, Function.identity());
    private int currentTransactionStartIndex = 0;

    private VObject model;

    private final Map<Object, Subscription> recursiveCollectionSubscriptions = new IdentityHashMap<>();
    private final Map<Object, Subscription> localCollectionSubscriptions = new IdentityHashMap<>();
    private Set<Object> preparedMemberships;

    // The relays this observer registers with the property supports of the objects it observes. Each runs the next
    // relay handler of the event, nearest observer first (E8, DeliveryOrder).
    // Its keys are the objects this observer observes recursively.
    private final Map<VObjectInternal, PropertyRelay> recursivePropertyRelays = new IdentityHashMap<>();
    private PropertyRelay localPropertyRelay;
    // This observer's relay handlers for property events.
    private final Consumer<Object> recursivePropertyHandler;
    private final Consumer<Object> localPropertyHandler;

    private boolean modelVersioningEnabled;
    private Subscription modelVersioningSubscription;
    private Consumer<Change> journalCapture;
    private Predicate<Change> journalPreparedDelivery;

    private boolean recording;
    private boolean recursiveObservationActive;
    private boolean localObservationActive;
    // Whether the local relay was registered on the object before the recursive relay (E7).
    private boolean localRelayFirst;

    private long timestamp;
    private final AtomicLong modelVersionNumber = new AtomicLong(0);

    private ModelVersion modelVersion = new ModelVersionImpl(System.currentTimeMillis(), 0);

    private static boolean isContainmentProperty(VObjectInternal owner, int propertyId) {
        for (int id : owner._vmf_getChildrenIndices()) {
            if (id == propertyId) return true;
        }
        return false;
    }

    /** Registers the journal's internal capture before observers can make further changes. */
    public static Subscription captureBeforeObservers(Changes changes, Consumer<Change> capture) {
        return captureBeforeObservers(changes, capture, null);
    }

    /** Registers a journal capture and its eligibility check at the native recording relay. */
    public static Subscription captureBeforeObservers(Changes changes, Consumer<Change> capture,
                                                       Predicate<Change> preparedDelivery) {
        if (!(changes instanceof ChangesImpl)) return () -> {};
        var impl = (ChangesImpl) changes;
        impl.journalCapture = capture;
        impl.journalPreparedDelivery = preparedDelivery;
        return () -> {
            if (impl.journalCapture == capture) {
                impl.journalCapture = null;
                impl.journalPreparedDelivery = null;
            }
        };
    }

    /** Early and public deliveries may wrap one origin event in separate native changes. */
    public static Object deliveryIdentity(Change change) {
        if (change instanceof PropChangeImpl) return ((PropChangeImpl) change).deliveryIdentity();
        if (change instanceof MapChangeImpl && MembershipPreparation.available()) return ((MapChangeImpl) change).deliveryIdentity();
        if (change instanceof SetChangeImpl && MembershipPreparation.available()) return ((SetChangeImpl) change).deliveryIdentity();
        return change instanceof ListChangeImpl ? ((ListChangeImpl) change).deliveryIdentity() : null;
    }

    @SuppressWarnings({"deprecation"})
    public ChangesImpl() {
        recursivePropertyHandler = event -> observePropertyChange((PropertyChangeEvent) event);
        localPropertyHandler = event -> firePropertyChange((PropertyChangeEvent) event, localPropertyNames,
                nonRecursiveChangeListeners);
    }

    /** The relay this observer registers with an object's property support, and its handler (E8). */
    private final class PropertyRelay extends DeliveryOrder.Handler implements VMFPropertyChangeSupport.PreparingListener {
        PropertyRelay(boolean recursive) {
            super(ChangesImpl.this, model, recursive, recursive ? recursivePropertyHandler : localPropertyHandler);
        }

        @Override public void prepare(PropertyChangeEvent event) {
            DeliveryOrder.prepare(this, event);
            if (recursive && capturesProperty(event)) journalCapture.accept(ChangesImpl.this.propertyChange(event));
        }

        @Override public void propertyChange(PropertyChangeEvent event) {
            DeliveryOrder.deliver(this, event);
        }
    }

    private boolean capturesProperty(PropertyChangeEvent event) {
        return recording && journalCapture != null && event.getSource() instanceof VObject
                && requiresPreparedProperty(event);
    }

    private static boolean requiresPreparedProperty(PropertyChangeEvent event) {
        if (ChangeInternal.CHANGE_TYPE_ORDINARY_PROPERTY.equals(propertyEventInfo(event))
                || !(event.getOldValue() instanceof VObject) && !(event.getNewValue() instanceof VObject)) return true;
        if (!(event.getSource() instanceof VObjectInternal)) return false;
        var owner = (VObjectInternal) event.getSource();
        return isContainmentProperty(owner, owner._vmf_getPropertyIdByName(event.getPropertyName()));
    }

    private static String propertyEventInfo(PropertyChangeEvent event) {
        return event instanceof VMFPropertyChangeSupport.VMFPropertyChangeEvent
                ? ((VMFPropertyChangeSupport.VMFPropertyChangeEvent) event).getEventInfo() : "";
    }

    private PropChangeImpl propertyChange(PropertyChangeEvent event) {
        String info = propertyEventInfo(event);
        if (ChangeInternal.CHANGE_TYPE_ORDINARY_PROPERTY.equals(info)) info = "";
        return new PropChangeImpl((VObject) event.getSource(), event.getPropertyName(),
                event.getOldValue(), event.getNewValue(), info, capturesProperty(event) ? event : null);
    }

    private void observePropertyChange(PropertyChangeEvent event) {
        Object oldValue = event.getOldValue();
        Object newValue = event.getNewValue();
        if (!(event.getSource() instanceof VObjectInternal)
                || (!(oldValue instanceof VObjectInternalModifiable) && !(newValue instanceof VObjectInternalModifiable))) {
            firePropertyChange(event, recursivePropertyNames, changeListeners);
            return;
        }
        var owner = (VObjectInternal) event.getSource();
        int propertyId = owner._vmf_getPropertyIdByName(event.getPropertyName());
        if (!isContainmentProperty(owner, propertyId)) {
            firePropertyChange(event, recursivePropertyNames, changeListeners);
            return;
        }
        try {
            firePropertyChange(event, recursivePropertyNames, changeListeners);
        } finally {
            if (owner._vmf_getPropertyValueById(propertyId) == newValue) observeAttachedChild(owner, newValue);
            stopObservingDetachedChild(owner, propertyId, null, oldValue);
        }
    }

    // A root event can reach both the local and recursive subscriptions. The
    // second delivery normally follows directly, but callbacks can interleave
    // other events or remove one of the subscriptions from the live model.
    private Object pendingScopeEvent;
    private boolean pendingScopeHasPeer;
    private ReferenceQueue<Object> expiredScopeEvents;
    private Set<ScopeEvent> overlappingScopeEvents;

    private boolean deferringHistory;
    private ArrayDeque<Object[]> deferredHistory;
    private long historyGeneration;

    private static Set<String> exactPropertyNames(List<Registration> listeners) {
        int count = listeners.size();
        if (count == 0) return Collections.emptySet();
        ChangeListener first = listeners.get(0).listener;
        if (!(first instanceof NamedChangeListener)) return null;
        String firstName = ((NamedChangeListener) first).propertyName;
        if (count == 1) return Collections.singleton(firstName);

        Set<String> names = null;
        for (int i = 1; i < count; i++) {
            ChangeListener listener = listeners.get(i).listener;
            if (!(listener instanceof NamedChangeListener)) return null;
            String name = ((NamedChangeListener) listener).propertyName;
            if (!firstName.equals(name)) {
                if (names == null) {
                    names = new HashSet<>();
                    names.add(firstName);
                }
                names.add(name);
            }
        }
        return names == null ? Collections.singleton(firstName) : names;
    }

    private boolean needsChange(Set<String> propertyNames, String name) {
        return propertyNames == null || recording || propertyNames.contains(name);
    }

    private void firePropertyChange(PropertyChangeEvent evt, Set<String> propertyNames, List<Registration> listeners) {
        if (isSilentRelay(evt.getSource(), listeners)) return;
        long stamp = DeliveryOrder.stamp(evt);
        String name = evt.getPropertyName();
        boolean firstDelivery = firstDelivery(evt.getSource(), evt);
        if (needsDelivery(evt.getSource(), propertyNames, listeners, name)) {
            var change = propertyChange(evt);
            // A newly opened journal checkpoint already includes properties assigned
            // before this delivery began. Their live notifications may still reach this relay.
            boolean record = firstDelivery && recording
                    && (!requiresPreparedProperty(evt) || isJournalDelivery(change));
            fireChange(change, record, deliveryTargets(evt.getSource(), listeners), stamp);
        }
    }

    private boolean isJournalDelivery(Change change) {
        return journalPreparedDelivery == null || journalPreparedDelivery.test(change);
    }

    private void fireListChange(VObject object, String name, CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> evt,
                                Set<String> propertyNames, List<Registration> listeners, boolean referenceSet) {
        if (isSilentRelay(object, listeners)) return;
        long stamp = DeliveryOrder.stamp(evt);
        boolean firstDelivery = firstDelivery(object, evt);
        if (needsDelivery(object, propertyNames, listeners, name)) {
            var change = listChange(object, name, evt, referenceSet);
            // A recorder opened during opposite delivery starts from the already committed list.
            boolean record = firstDelivery && recording
                    && (!(evt.source() instanceof ObservableModelList
                        && ((ObservableModelList<?>) evt.source()).hasReciprocalCollectionOpposite())
                        || isJournalDelivery(change));
            fireChange(change, record, deliveryTargets(object, listeners), stamp);
        }
    }

    private static Change listChange(VObject object, String name,
                                     CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> event,
                                     boolean referenceSet) {
        return referenceSet ? new RefSetChangeImpl(object, name, event) : new ListChangeImpl(object, name, event);
    }

    private boolean firstDelivery(Object source, Object event) {
        if (source != model) return true;
        if (pendingScopeEvent == event) {
            pendingScopeEvent = null;
            return false;
        }
        if (overlappingScopeEvents != null) {
            ScopeEvent expired;
            while ((expired = (ScopeEvent) expiredScopeEvents.poll()) != null) {
                overlappingScopeEvents.remove(expired);
            }
            if (!overlappingScopeEvents.isEmpty() && overlappingScopeEvents.remove(new ScopeEvent(event, null))) {
                return false;
            }
        }
        boolean mayReachBoth = recursiveObservationActive && !nonRecursiveChangeListeners.isEmpty();
        if (recording || mayReachBoth) {
            if (pendingScopeEvent != null && (mayReachBoth || pendingScopeHasPeer)) {
                releasePendingEvent();
            }
            pendingScopeEvent = event;
            pendingScopeHasPeer = mayReachBoth;
        }
        return true;
    }

    // A dispatcher may still hold a subscription snapshot across stop, clear or
    // unsubscribe. Keep its deduplication identity without retaining a removed subtree.
    private void releasePendingEvent() {
        if (pendingScopeEvent == null) return;
        if (overlappingScopeEvents == null) {
            expiredScopeEvents = new ReferenceQueue<>();
            overlappingScopeEvents = new HashSet<>();
        }
        overlappingScopeEvents.add(new ScopeEvent(pendingScopeEvent, expiredScopeEvents));
        pendingScopeEvent = null;
    }

    private static final class ScopeEvent extends WeakReference<Object> {
        private final int hash;

        private ScopeEvent(Object event, ReferenceQueue<Object> queue) {
            super(event, queue);
            hash = System.identityHashCode(event);
        }

        @Override public int hashCode() {
            return hash;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object event = get();
            return event != null && other instanceof ScopeEvent && event == ((ScopeEvent) other).get();
        }
    }

    private static Throwable notifyListeners(List<Registration> listeners, Change change) {
        int count = listeners.size();
        if (count == 0 || isRecordingOnlyClear(change)) return null;
        if (count == 1) {
            return notifyListener(listeners.get(0).listener, change, null);
        }
        Throwable failure = null;
        // A callback can subscribe or unsubscribe during dispatch.
        for (Registration registration : listeners) {
            failure = notifyListener(registration.listener, change, failure);
        }
        return failure;
    }

    /**
     * Clearing a single-valued reference whose opposite is a list first removes the owner from that list, which
     * already reports the cleared reference to listeners. The setter's follow-up change only serves recording.
     */
    private static boolean isRecordingOnlyClear(Change change) {
        if (!(change instanceof PropChangeImpl)) return false;
        var property = (PropChangeImpl) change;
        return property.newValue() == null
                && ChangeInternal.CHANGE_TYPE_SCALAR_AFTER_OPPOSITE_REMOVAL.equals(property.getInternalChangeInfo());
    }

    private static Throwable notifyListener(ChangeListener listener, Change change, Throwable failure) {
        try {
            listener.onChange(change);
        } catch (RuntimeException | Error ex) {
            return ObserverFailures.add(failure, ex);
        }
        return failure;
    }

    private void fireChange(Change change, boolean record, List<Registration> listeners, long stamp) {
        if (!record || !isPrimaryChange(change)) {
            ObserverFailures.rethrow(notifyListeners(listeners, change));
            return;
        }
        long generation = historyGeneration;
        if (listeners.isEmpty()) {
            ObserverFailures.rethrow(recordChange(change, generation, null, stamp));
            return;
        }
        boolean scalarEdit = change instanceof MapChangeImpl || change instanceof SetChangeImpl;
        if (change instanceof PropChangeImpl) {
            var property = (PropChangeImpl) change;
            scalarEdit = !(property.oldValue() instanceof VObject) && !(property.newValue() instanceof VObject);
        }
        if (scalarEdit) {
            // Scalars and map entries already changed. Record them before edits made by their observers.
            if (deferringHistory) {
                deferChange(change, stamp);
                ObserverFailures.rethrow(notifyListeners(listeners, change));
                return;
            }
            deferringHistory = true;
            try {
                ObserverFailures.rethrow(appendHistory(change, generation, notifyListeners(listeners, change), stamp));
            } finally {
                deferringHistory = false;
            }
            return;
        }
        // A removed subtree stays observable through its detach notification.
        // Preserve the native ordering of edits made there before the removal.
        var failure = notifyListeners(listeners, change);
        ObserverFailures.rethrow(recordChange(change, generation, failure, stamp));
    }

    private static boolean isPrimaryChange(Change change) {
        var object = (VObjectInternal) change.object();
        int property = object._vmf_getPropertyIdByName(change.propertyName());
        for (int parent : object._vmf_getParentIndices()) {
            if (parent == property) return false;
        }
        return !ChangeInternal.isCrossRefChange(change);
    }

    private Throwable recordChange(Change change, long generation, Throwable failure, long stamp) {
        if (!recording || generation != historyGeneration) return failure;
        if (change instanceof PropChangeImpl) {
            var property = (PropChangeImpl) change;
            if (ChangeInternal.CHANGE_TYPE_SCALAR_AFTER_OPPOSITE_REMOVAL.equals(property.getInternalChangeInfo())
                    && OppositeReferenceRemoval.wasRecorded(property.object(), this, generation)) {
                // The indexed inverse removal cleared the scalar first. Keep the original listener payload,
                // but record the actual remaining edit so replay and undo apply the two operations in order.
                if (property.newValue() == null) return failure;
                change = new PropChangeImpl(property.object(), property.propertyName(), null, property.newValue(),
                        property.getTimestamp(), ChangeInternal.CHANGE_TYPE_EMPTY);
            }
        } else if (change instanceof ListChangeImpl) {
            OppositeReferenceRemoval.record((ListChangeImpl) change, this, generation);
        }
        if (deferringHistory) {
            deferChange(change, stamp);
            return failure;
        }
        deferringHistory = true;
        try {
            return appendHistory(change, generation, failure, stamp);
        } finally {
            deferringHistory = false;
        }
    }

    private void deferChange(Change change, long stamp) {
        if (deferredHistory == null) deferredHistory = new ArrayDeque<>();
        deferredHistory.addLast(new Object[]{change, stamp});
    }

    private Throwable appendHistory(Change change, long generation, Throwable failure, long stamp) {
        if (recording && generation == historyGeneration) failure = addHistory(change, failure, stamp);
        while (deferredHistory != null && !deferredHistory.isEmpty()) {
            Object[] nested = deferredHistory.removeFirst();
            if (recording) failure = addHistory((Change) nested[0], failure, (Long) nested[1]);
        }
        return failure;
    }

    /**
     * Adds a change in delivery order (H4): before the changes of the current group whose events were stamped
     * later. Those were made by listeners while this change was delivered to other observers first.
     */
    private Throwable addHistory(Change change, Throwable failure, long stamp) {
        int index = all.size();
        if (stamp > 0) {
            while (index > currentTransactionStartIndex && historyStamps.get(index - 1) > stamp) index--;
        }
        try {
            all.add(index, change);
            historyStamps.add(index, stamp);
        } catch (RuntimeException | Error ex) {
            return ObserverFailures.add(failure, ex);
        }
        return failure;
    }

    public void setModel(VObject model) {
        this.model = model;
    }

    /** Whether both relays observe the object itself: the local one and the recursive one. */
    private boolean bothRelaysObserveModel() {
        return localObservationActive && recursiveObservationActive;
    }

    /** The relay of the object itself that was registered first; it delivers for both scopes (E7). */
    private boolean isDeliveringRelay(List<Registration> listeners) {
        return localRelayFirst ? listeners == nonRecursiveChangeListeners : listeners == changeListeners;
    }

    /**
     * When both relays observe the object, the one registered first calls the listeners of both scopes, in the
     * order they were added, and the other one stays silent for the object's own events (E7).
     */
    private boolean isSilentRelay(Object source, List<Registration> listeners) {
        return source == model && bothRelaysObserveModel() && !isDeliveringRelay(listeners);
    }

    /** The listeners an event of this relay reaches (E7). */
    private List<Registration> deliveryTargets(Object source, List<Registration> listeners) {
        if (source != model || !bothRelaysObserveModel()) return listeners;
        var merged = new ArrayList<Registration>(changeListeners.size() + nonRecursiveChangeListeners.size());
        merged.addAll(changeListeners);
        merged.addAll(nonRecursiveChangeListeners);
        merged.sort(Comparator.comparingLong(registration -> registration.sequence));
        return merged;
    }

    /** Whether a relay creates a change for the property; the delivering relay serves both scopes. */
    private boolean needsDelivery(Object source, Set<String> propertyNames, List<Registration> listeners, String name) {
        if (source == model && bothRelaysObserveModel()) {
            return needsChange(recursivePropertyNames, name) || needsChange(localPropertyNames, name);
        }
        return needsChange(propertyNames, name);
    }

    @Override
    public void start() {

        clear();

        if (!recording) DeliveryOrder.recordingChanged(true);
        recording = true;

        updateRecursiveObservation();

        enableModelVersioning();
    }

    private void updateRecursiveObservation() {
        boolean shouldObserve = recording || !changeListeners.isEmpty();
        if (shouldObserve == recursiveObservationActive) {
            return;
        }
        recursiveObservationActive = shouldObserve;
        if (shouldObserve) {
            localRelayFirst = localObservationActive;
            observeSubtree((VObjectInternal) model, true);
        } else {
            recursivePropertyRelays.forEach((object, relay) -> {
                object.removePropertyChangeListener(relay);
                DeliveryOrder.detach(relay);
            });
            recursivePropertyRelays.clear();
            recursiveCollectionSubscriptions.values().forEach(Subscription::unsubscribe);
            recursiveCollectionSubscriptions.clear();
        }
    }

    private void observeAttachedChild(VObjectInternal owner, Object value) {
        if (recursiveObservationActive && value instanceof VObjectInternalModifiable) {
            var child = (VObjectInternalModifiable) value;
            if (child._vmf_getContainer() == owner && !recursivePropertyRelays.containsKey(child)
                    && recursivePropertyRelays.containsKey(owner)) {
                observeSubtree(child, true);
            }
        }
    }

    private void stopObservingDetachedChild(VObjectInternal formerOwner, int formerPropertyId,
                                            Set<Object> currentMembers, Object value) {
        if (!(value instanceof VObjectInternalModifiable) || value == model
                || !recursivePropertyRelays.containsKey(value)) return;
        var child = (VObjectInternalModifiable) value;
        VObject parent = child._vmf_getContainer();
        if (parent instanceof VObjectInternal && recursivePropertyRelays.containsKey(parent)) {
            var owner = (VObjectInternal) parent;
            for (int id : owner._vmf_getChildrenIndices()) {
                // During source removal, the generated parent pointer can still point to the old owner.
                // Confirm the edge in the current property, including after a nested reparent or re-add.
                if (owner == formerOwner && id == formerPropertyId && currentMembers != null) {
                    if (currentMembers.contains(child)) return;
                } else if (containsChild(owner._vmf_getPropertyValueById(id), child)) {
                    return;
                }
            }
        }
        observeSubtree(child, false);
    }

    private static boolean containsChild(Object property, Object child) {
        if (property == child) return true;
        if (property instanceof List<?>) {
            for (Object item : (List<?>) property) {
                if (item == child) return true;
            }
        }
        return false;
    }

    /** Visits only live parent/child edges, with parents and list elements in containment order. */
    private void observeSubtree(VObjectInternal root, boolean attach) {
        var position = attach ? new DeliveryOrder.Position(model) : null;
        var pending = new ArrayDeque<VObjectInternal>();
        pending.push(root);
        while (!pending.isEmpty()) {
            VObjectInternal object = pending.pop();
            if (attach) {
                var relay = new PropertyRelay(true);
                var present = recursivePropertyRelays.put(object, relay);
                if (present != null) {
                    recursivePropertyRelays.put(object, present);
                    continue;
                }
                subscribeCollections(object, true, position);
                DeliveryOrder.attach(object, relay, position);
                object.addPropertyChangeListener(relay);
            } else {
                var relay = recursivePropertyRelays.remove(object);
                if (relay == null) continue;
                unsubscribeCollections(object, recursiveCollectionSubscriptions);
                object.removePropertyChangeListener(relay);
                DeliveryOrder.detach(relay);
            }

            int[] children = object._vmf_getChildrenIndices();
            for (int i = children.length - 1; i >= 0; i--) {
                Object value = object._vmf_getPropertyValueById(children[i]);
                if (value instanceof List<?>) {
                    List<?> list = (List<?>) value;
                    for (var iterator = list.listIterator(list.size()); iterator.hasPrevious();) {
                        pushAttachedChild(pending, object, iterator.previous());
                    }
                } else {
                    pushAttachedChild(pending, object, value);
                }
            }
        }
    }

    private static void pushAttachedChild(Deque<VObjectInternal> pending, VObjectInternal owner, Object value) {
        if (value instanceof VObjectInternalModifiable) {
            var child = (VObjectInternalModifiable) value;
            if (child._vmf_getContainer() == owner) pending.push(child);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerChangeListenerNonRecursive(VObject vObj) {
        var position = new DeliveryOrder.Position(model);
        subscribeCollections((VObjectInternal) vObj, false, position);

        VObjectInternal obj = (VObjectInternal) vObj;
        localPropertyRelay = new PropertyRelay(false);
        DeliveryOrder.attach(obj, localPropertyRelay, position);
        obj.addPropertyChangeListener(localPropertyRelay);
    }

    @SuppressWarnings("unchecked")
    private void unregisterChangeListenerNonRecursive(VObject vObj) {
        unsubscribeCollections((VObjectInternal) vObj, localCollectionSubscriptions);

        VObjectInternal obj = (VObjectInternal) vObj;
        if (localPropertyRelay != null) {
            obj.removePropertyChangeListener(localPropertyRelay);
            DeliveryOrder.detach(localPropertyRelay);
            localPropertyRelay = null;
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void subscribeCollections(VObjectInternal owner, boolean recursive, DeliveryOrder.Position position) {
        Map<Object, Subscription> subscriptions = recursive ? recursiveCollectionSubscriptions : localCollectionSubscriptions;
        int[] types = owner._vmf_getPropertyTypes();
        for (int id = 0; id < types.length; id++) {
            if (!isObservedCollection(types[id])) continue;
            String name = owner._vmf_getPropertyNames()[id];
            if (types[id] == VObjectInternal.VMF_TYPE_MAP) {
                var map = (VMap<Object, Object>) owner._vmf_getPropertyValueById(id);
                var preparation = recursive && MembershipPreparation.available() ? MembershipPreparation.observe(map, event -> {
                    if (recording && journalCapture != null) journalCapture.accept(new MapChangeImpl(owner, name, event));
                }) : Optional.<Subscription>empty();
                Consumer<VMapChangeEvent<Object, Object>> handler = event -> {
                    var scope = recursive ? changeListeners : nonRecursiveChangeListeners;
                    if (isSilentRelay(owner, scope)) return;
                    long stamp = DeliveryOrder.stamp(event);
                    boolean first = firstDelivery(owner, event);
                    if (needsDelivery(owner, recursive ? recursivePropertyNames : localPropertyNames, scope, name)) {
                        var change = new MapChangeImpl(owner, name, event);
                        fireChange(change, recordsMembership(map, change, first), deliveryTargets(owner, scope), stamp);
                    }
                };
                var relay = relay(map, recursive, position, handler,
                        attached -> map.addChangeListener(event -> DeliveryOrder.deliver(attached, event)));
                subscriptions.put(map, prepareMembership(map, preparation, relay));
            } else if (types[id] == VObjectInternal.VMF_TYPE_SET) {
                var set = (VSet<Object>) owner._vmf_getPropertyValueById(id);
                var preparation = recursive && MembershipPreparation.available() ? MembershipPreparation.observe(set, event -> {
                    if (recording && journalCapture != null) journalCapture.accept(new SetChangeImpl(owner, name, event));
                }) : Optional.<Subscription>empty();
                Consumer<VSetChangeEvent<Object>> handler = event -> {
                    var scope = recursive ? changeListeners : nonRecursiveChangeListeners;
                    if (isSilentRelay(owner, scope)) return;
                    long stamp = DeliveryOrder.stamp(event);
                    boolean first = firstDelivery(owner, event);
                    if (needsDelivery(owner, recursive ? recursivePropertyNames : localPropertyNames, scope, name)) {
                        var change = new SetChangeImpl(owner, name, event);
                        fireChange(change, recordsMembership(set, change, first), deliveryTargets(owner, scope), stamp);
                    }
                };
                var relay = relay(set, recursive, position, handler,
                        attached -> set.addChangeListener(event -> DeliveryOrder.deliver(attached, event)));
                subscriptions.put(set, prepareMembership(set, preparation, relay));
            } else {
                var list = (VList<Object>) owner._vmf_getPropertyValueById(id);
                int propertyId = id;
                boolean referenceSet = ReferenceCollections.isReferenceList(list) ||
                        (owner._vmf_isReadOnly() && owner._vmf_getPublicPropertyValueById(id) instanceof RefSet);
                Subscription subscription;
                if (recursive && isContainmentProperty(owner, id)) {
                    var containment = ReferenceCollections.containment(list);
                    if (!referenceSet && list instanceof ContainmentList) {
                        subscription = listRelay(list, true, position,
                                event -> observeContainmentListChange(owner, propertyId, list, name, event, false),
                                event -> {
                                    if (recording && journalCapture != null) {
                                        journalCapture.accept(new ListChangeImpl(owner, name, event));
                                    }
                                });
                    } else if (list instanceof ObservableModelList && containment != null) {
                        subscription = listRelay(list, true, position,
                                event -> observeContainmentListChange(owner, propertyId, list, name, event, referenceSet),
                                event -> {
                                    // The owning set's relay runs after ownership repair. Its installed membership
                                    // can already be removed by a nested public callback.
                                    if (recording && journalCapture != null && (containment.keys != null
                                            || !(owner._vmf_getPublicPropertyValueById(propertyId) instanceof OrderedRefSet)
                                            || event.wasRemoved()
                                            && ChangeInternal.CHANGE_TYPE_CONTAINMENT.equals(event.eventInfo()))) {
                                        journalCapture.accept(listChange(owner, name, event, referenceSet));
                                    }
                                });
                    } else {
                        subscription = listRelay(list, true, position,
                                event -> observeContainmentListChange(owner, propertyId, list, name, event, referenceSet), null);
                    }
                } else if (recursive && list instanceof ObservableModelList) {
                    var observable = (ObservableModelList<Object>) list;
                    boolean capturePositional = observable.isOrdinary() || observable.hasReciprocalCollectionOpposite()
                            || !referenceSet && Arrays.stream(owner._vmf_getIndicesOfPropertiesWithModelElementTypes())
                                    .noneMatch(index -> index == propertyId);
                    subscription = listRelay(list, true, position,
                            event -> fireListChange(owner, name, event, recursivePropertyNames, changeListeners, referenceSet),
                            event -> {
                                if (recording && journalCapture != null
                                        && (capturePositional && !ChangeInternal.CHANGE_TYPE_CROSS_REF.equals(event.eventInfo())
                                            || OppositeReferenceRemoval.isCommittedSourceRemoval(event))) {
                                    journalCapture.accept(listChange(owner, name, event, referenceSet));
                                }
                            });
                } else if (recursive) {
                    subscription = listRelay(list, true, position,
                            event -> fireListChange(owner, name, event, recursivePropertyNames, changeListeners, referenceSet), null);
                } else {
                    subscription = listRelay(list, false, position,
                            event -> fireListChange(owner, name, event, localPropertyNames, nonRecursiveChangeListeners, referenceSet), null);
                }
                subscriptions.put(list, subscription);
            }
        }
    }

    /**
     * Registers a relay slot with a dispatcher and adds this observer's handler to the dispatcher's relay handlers.
     * The dispatcher calls the slots in registration order; each slot runs the next handler, nearest observer
     * first (E8).
     */
    @SuppressWarnings("unchecked")
    private <E> Subscription relay(Object dispatcher, boolean recursive, DeliveryOrder.Position position,
                                   Consumer<E> handler, Function<DeliveryOrder.Handler, Subscription> register) {
        var attached = new DeliveryOrder.Handler(this, model, recursive, (Consumer<Object>) (Consumer<?>) handler);
        DeliveryOrder.attach(dispatcher, attached, position);
        Subscription slot;
        try {
            slot = register.apply(attached);
        } catch (RuntimeException | Error ex) {
            DeliveryOrder.detach(attached);
            throw ex;
        }
        return () -> {
            DeliveryOrder.detach(attached);
            slot.unsubscribe();
        };
    }

    /** A list relay; VMF's own lists also let the slot take the dispatch order in their prepare phase. */
    @SuppressWarnings("unchecked")
    private Subscription listRelay(VList<Object> list, boolean recursive, DeliveryOrder.Position position,
                                   Consumer<CollectionChangeEvent<Object, VList<Object>, VListChange<Object>>> handler,
                                   Consumer<VListChangeEvent<Object>> capture) {
        var relay = new ListRelay(recursive, (Consumer<Object>) (Consumer<?>) handler, capture);
        DeliveryOrder.attach(list, relay, position);
        try {
            if (list instanceof ContainmentList) {
                relay.slot = ((ContainmentList<Object>) list).observe(relay, relay);
            } else if (list instanceof ObservableModelList) {
                relay.slot = ((ObservableModelList<Object>) list).observe(relay, relay);
            } else {
                relay.slot = list.addChangeListener(relay);
            }
        } catch (RuntimeException | Error ex) {
            DeliveryOrder.detach(relay);
            throw ex;
        }
        return relay;
    }

    /**
     * The relay slot this observer registers with a list: its handler, the list's listener and prepare phase, and the
     * subscription that removes it (E8).
     */
    private final class ListRelay extends DeliveryOrder.Handler
            implements CollectionChangeListener<Object, VList<Object>, VListChange<Object>>,
            Consumer<VListChangeEvent<Object>>, Subscription {
        private final Consumer<VListChangeEvent<Object>> capture;
        private Subscription slot;

        ListRelay(boolean recursive, Consumer<Object> handler, Consumer<VListChangeEvent<Object>> capture) {
            super(ChangesImpl.this, model, recursive, handler);
            this.capture = capture;
        }

        @Override public void onChange(CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> event) {
            DeliveryOrder.deliver(this, event);
        }

        /** The prepare phase of VMF's own lists. */
        @Override public void accept(VListChangeEvent<Object> event) {
            DeliveryOrder.prepare(this, event);
            if (capture != null) capture.accept(event);
        }

        @Override public void unsubscribe() {
            DeliveryOrder.detach(this);
            slot.unsubscribe();
        }
    }

    private boolean recordsMembership(Object collection, Change change, boolean first) {
        return first && recording && (journalPreparedDelivery == null || preparedMemberships == null
                || !preparedMemberships.contains(collection) || journalPreparedDelivery.test(change));
    }

    private Subscription prepareMembership(Object collection, Optional<Subscription> preparation, Subscription relay) {
        if (preparation.isEmpty()) return relay;
        if (preparedMemberships == null) preparedMemberships = Collections.newSetFromMap(new IdentityHashMap<>());
        preparedMemberships.add(collection);
        return () -> {
            preparedMemberships.remove(collection);
            preparation.get().unsubscribe();
            relay.unsubscribe();
        };
    }

    private void observeContainmentListChange(VObjectInternal owner, int propertyId, VList<Object> list, String name,
                                              CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> event, boolean referenceSet) {
        try {
            fireListChange(owner, name, event, recursivePropertyNames, changeListeners, referenceSet);
        } finally {
            List<Object> added = event.added().elements();
            List<Object> removed = event.removed().elements();
            Set<Object> currentMembers = null;
            if (added.size() + removed.size() > 1) {
                currentMembers = Collections.newSetFromMap(new IdentityHashMap<>());
                currentMembers.addAll(list);
            }
            for (Object child : added) {
                if (currentMembers == null ? containsChild(list, child) : currentMembers.contains(child)) {
                    observeAttachedChild(owner, child);
                }
            }
            for (Object child : removed) {
                stopObservingDetachedChild(owner, propertyId, currentMembers, child);
            }
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void unsubscribeCollections(VObjectInternal owner, Map<Object, Subscription> subscriptions) {
        int[] types = owner._vmf_getPropertyTypes();
        for (int id = 0; id < types.length; id++) {
            if (isObservedCollection(types[id])) {
                Subscription subscription = subscriptions.remove(owner._vmf_getPropertyValueById(id));
                if (subscription != null) subscription.unsubscribe();
            }
        }
    }

    private static boolean isObservedCollection(int type) {
        return type == -2 || type == VObjectInternal.VMF_TYPE_MAP || type == VObjectInternal.VMF_TYPE_SET;
    }

    @Override
    public void startTransaction() {

        if (!recording) {
            throw new RuntimeException("Please call 'start()' before starting"
                    + " a transaction.");
        }

        currentTransactionStartIndex = all.size();
    }

    static class TransactionImpl implements Transaction {

        private final List<Change> changes;

        public TransactionImpl(List<Change> changes) {
            this.changes = List.copyOf(changes);
        }

        @Override
        public List<Change> changes() {
            return this.changes;
        }

        @Override
        public boolean isUndoable() {

            for (int i = changes.size() - 1; i >= 0; i--) {
                Change change = changes.get(i);
                if (change.isUndoable()) continue;

                boolean superseded = false;
                for (int j = i + 1; j < changes.size(); j++) {
                    Change later = changes.get(j);
                    if (later.object() == change.object() && later.propertyName().equals(change.propertyName())) {
                        superseded = true;
                        break;
                    }
                }
                if (!superseded) return false;
            }
            return true;
        }

        @Override
        public void undo() {
            if (!isUndoable()) return;
            for (int i = changes.size() - 1; i > -1; i--) {
                changes.get(i).undo();
            }
        }

        /**
         * Reduces two property changes into one change. This only works if they both denote changes of the same property.
         * Other forms of changes (e.g. list changes) are not supported.
         * @param c1 first event
         * @param c2 second event
         * @return reduced change that combines the two specified changes
         */
        static Change reduce(Change c1, Change c2) {

            if( (c1.object()!=c2.object()) || (!c1.propertyName().equals(c2.propertyName())) ) {
                throw new UnsupportedOperationException("Cannot reduce changes that don't affect the same property and/or object");
            }

            if(!c1.propertyChange().isPresent() || !c2.propertyChange().isPresent()) {
                throw new UnsupportedOperationException("Cannot reduce list changes. Only property changes are supported.");
            }

            // swap change if first argument occured after second argument
            if(c1.getTimestamp() > c2.getTimestamp()) {
                Change swapChange;
                swapChange = c1;
                c1 = c2;
                c2 = swapChange;
            }

            PropChangeImpl pc1 = (PropChangeImpl)c1.propertyChange().get();
            PropChangeImpl pc2 = (PropChangeImpl)c2.propertyChange().get();

            Change result = new PropChangeImpl(c1.object(), c1.propertyName(),
                    pc1.oldValue(), pc2.newValue(), c2.getTimestamp(), pc2.getInternalChangeInfo()
            );

            return result;

        }

        static Change reduce(Change... changes) {
            return reduce(Arrays.asList(changes));
        }

        static Change reduce(List<Change> changes) {

            if(changes.size()==0) {
                throw new UnsupportedOperationException("Cannot reduce empty list.");
            }

            if(changes.size()==1) {
                return changes.get(0);
            }

            List<Change> toReduce = new ArrayList<>(changes);
            Collections.sort(toReduce, Comparator.comparingLong(Change::getTimestamp));

            Change c1 = toReduce.get(0);
            Change c2 = toReduce.get(toReduce.size()-1);

            return reduce(c1,c2);

        }
    }

    @Override
    public void publishTransaction() {
        int end = all.size();
        if (currentTransactionStartIndex < end) {
            var transaction = new TransactionImpl(unmodifiableAll.subList(currentTransactionStartIndex, end));
            // Adding to the observable list commits the transaction before it calls any listeners.
            // Reserve this range before dispatch so a listener can publish the following range.
            currentTransactionStartIndex = end;
            transactions.add(transaction);
        }
    }

    @Override
    public void stop() {
        try {
            if (currentTransactionStartIndex < all.size()) {
                publishTransaction();
            }
        } finally {
            // A publication listener can record additional edits while stop is in progress.
            // Keep those edits in all(), without leaving a group pending after recording ends.
            currentTransactionStartIndex = all.size();
            if (recording) DeliveryOrder.recordingChanged(false);
            recording = false;
            releasePendingEvent();
            disableModelVersioning();
            updateRecursiveObservation();
        }
    }

    @Override
    public VList<Change> all() {
        return unmodifiableAll;
    }

    @Override
    public VList<Transaction> transactions() {
        return unmodifiableTransactions;
    }

    @Override
    public void clear() {
        historyGeneration++;
        releasePendingEvent();
        if (deferredHistory != null) deferredHistory.clear();
        currentTransactionStartIndex = 0;
        all.clear();
        historyStamps.clear();
        transactions.clear();
    }

    @Override
    public Subscription addListener(ChangeListener l) {
        return addListener(l, true);
    }

    @Override
    public Subscription addListener(ChangeListener l, boolean recursive) {
        Registration registration = new Registration(l, recursive);
        registration.listeners().add(registration);
        updateSubscriptions(recursive);
        return registration;
    }

    private final class Registration implements Subscription {
        final ChangeListener listener;
        final boolean recursive;
        final long sequence = ++registrationSequence;

        Registration(ChangeListener listener, boolean recursive) {
            this.listener = listener;
            this.recursive = recursive;
        }

        List<Registration> listeners() {
            return recursive ? changeListeners : nonRecursiveChangeListeners;
        }

        @Override public void unsubscribe() {
            if (listeners().remove(this)) {
                if (listeners().isEmpty()) releasePendingEvent();
                updateSubscriptions(recursive);
            }
        }
    }

    private void updateSubscriptions(boolean recursive) {
        if (recursive) {
            recursivePropertyNames = exactPropertyNames(changeListeners);
            updateRecursiveObservation();
            return;
        }
        localPropertyNames = exactPropertyNames(nonRecursiveChangeListeners);
        boolean needsLocalObservation = !nonRecursiveChangeListeners.isEmpty();
        if (needsLocalObservation == localObservationActive) return;
        if (needsLocalObservation) {
            localRelayFirst = !recursiveObservationActive;
            registerChangeListenerNonRecursive(model);
        }
        else unregisterChangeListenerNonRecursive(model);
        localObservationActive = needsLocalObservation;
    }

    @Override
    public Subscription addPropertyListener(String propertyName, ChangeListener listener, Scope scope) {
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(scope, "scope");
        return addListener(new NamedChangeListener(propertyName, listener), scope == Scope.RECURSIVE);
    }

    private static final class NamedChangeListener implements ChangeListener {
        private final String propertyName;
        private final ChangeListener listener;

        private NamedChangeListener(String propertyName, ChangeListener listener) {
            this.propertyName = propertyName;
            this.listener = listener;
        }

        @Override
        public void onChange(Change change) {
            if (propertyName.equals(change.propertyName())) {
                listener.onChange(change);
            }
        }
    }

    @Override
    public ModelVersion modelVersion() {
        return modelVersion;
    }

    public void enableModelVersioning() {

        // unsubscribe previous listener
        if (modelVersioningSubscription != null) {
            modelVersioningSubscription.unsubscribe();
            modelVersioningSubscription = null;
        }

        // register new listener
        modelVersioningSubscription = addListener((change) -> {
            timestamp = change.getTimestamp();
            modelVersionNumber.getAndIncrement();

            modelVersion = new ModelVersionImpl(
                    timestamp, modelVersionNumber.get());
        });

        this.modelVersioningEnabled = true;
    }

    public void disableModelVersioning() {

        if (recording) {
            throw new RuntimeException("Cannot disable model versioning during"
                    + " change recording."
                    + " Call stop() before disabling model versioning.");
        }

        if (modelVersioningSubscription != null) {
            modelVersioningSubscription.unsubscribe();
            modelVersioningSubscription = null;
        }

        this.modelVersioningEnabled = false;
    }

    @Override
    public boolean isModelVersioningEnabled() {
        return modelVersioningEnabled;
    }
}
