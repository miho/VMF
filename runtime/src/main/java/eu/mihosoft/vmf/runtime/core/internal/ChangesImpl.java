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
import eu.mihosoft.vmf.runtime.core.*;
import vjavax.observer.Subscription;
import vjavax.observer.collection.CollectionChangeEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

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
    private final VList<Change> unmodifiableAll = VMappedList.newUnmodifiableInstance(all, Function.identity());
    private final VList<Transaction> transactions
            = VList.newInstance(new ArrayList<>());
    private final VList<Transaction> unmodifiableTransactions =
            VMappedList.newUnmodifiableInstance(transactions, Function.identity());
    private int currentTransactionStartIndex = 0;

    private VObject model;

    private final Set<VObjectInternal> observedObjects = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Object, Subscription> recursiveCollectionSubscriptions = new IdentityHashMap<>();
    private final Map<Object, Subscription> localCollectionSubscriptions = new IdentityHashMap<>();

    private final PropertyChangeListener objListener;
    private final PropertyChangeListener nonRecursiveObjListener;

    private boolean modelVersioningEnabled;
    private Subscription modelVersioningSubscription;

    private boolean recording;
    private boolean recursiveObservationActive;
    private boolean localObservationActive;

    private long timestamp;
    private final AtomicLong modelVersionNumber = new AtomicLong(0);

    private ModelVersion modelVersion = new ModelVersionImpl(System.currentTimeMillis(), 0);

    private static boolean isContainmentProperty(VObjectInternal owner, int propertyId) {
        for (int id : owner._vmf_getChildrenIndices()) {
            if (id == propertyId) return true;
        }
        return false;
    }

    @SuppressWarnings({"deprecation"})
    public ChangesImpl() {
        objListener = this::observePropertyChange;
        nonRecursiveObjListener = evt -> firePropertyChange(evt, localPropertyNames, nonRecursiveChangeListeners);
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
    private ArrayDeque<Change> deferredHistory;
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
        String name = evt.getPropertyName();
        boolean firstDelivery = firstDelivery(evt.getSource(), evt);
        if (needsChange(propertyNames, name)) {
            String info = evt instanceof VMFPropertyChangeSupport.VMFPropertyChangeEvent
                    ? ((VMFPropertyChangeSupport.VMFPropertyChangeEvent) evt).getEventInfo() : "";
            var change = new PropChangeImpl((VObject) evt.getSource(), name, evt.getOldValue(), evt.getNewValue(), info);
            fireChange(change, firstDelivery && recording, listeners);
        }
    }

    private void fireListChange(VObject object, String name, CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> evt,
                                Set<String> propertyNames, List<Registration> listeners) {
        boolean firstDelivery = firstDelivery(object, evt);
        if (needsChange(propertyNames, name)) {
            fireChange(new ListChangeImpl(object, name, evt), firstDelivery && recording, listeners);
        }
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
        if (count == 0) return null;
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

    private static Throwable notifyListener(ChangeListener listener, Change change, Throwable failure) {
        try {
            listener.onChange(change);
        } catch (RuntimeException | Error ex) {
            return ObserverFailures.add(failure, ex);
        }
        return failure;
    }

    private void fireChange(Change change, boolean record, List<Registration> listeners) {
        if (!record || !isPrimaryChange(change)) {
            ObserverFailures.rethrow(notifyListeners(listeners, change));
            return;
        }
        long generation = historyGeneration;
        if (listeners.isEmpty()) {
            ObserverFailures.rethrow(recordChange(change, generation, null));
            return;
        }
        boolean scalarEdit = change instanceof MapChangeImpl;
        if (change instanceof PropChangeImpl) {
            var property = (PropChangeImpl) change;
            scalarEdit = !(property.oldValue() instanceof VObject) && !(property.newValue() instanceof VObject);
        }
        if (scalarEdit) {
            // Scalars and map entries already changed. Record them before edits made by their observers.
            if (deferringHistory) {
                deferChange(change);
                ObserverFailures.rethrow(notifyListeners(listeners, change));
                return;
            }
            deferringHistory = true;
            try {
                ObserverFailures.rethrow(appendHistory(change, generation, notifyListeners(listeners, change)));
            } finally {
                deferringHistory = false;
            }
            return;
        }
        // A removed subtree stays observable through its detach notification.
        // Preserve the native ordering of edits made there before the removal.
        var failure = notifyListeners(listeners, change);
        ObserverFailures.rethrow(recordChange(change, generation, failure));
    }

    private static boolean isPrimaryChange(Change change) {
        var object = (VObjectInternal) change.object();
        int property = object._vmf_getPropertyIdByName(change.propertyName());
        for (int parent : object._vmf_getParentIndices()) {
            if (parent == property) return false;
        }
        return !ChangeInternal.isCrossRefChange(change);
    }

    private Throwable recordChange(Change change, long generation, Throwable failure) {
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
            deferChange(change);
            return failure;
        }
        deferringHistory = true;
        try {
            return appendHistory(change, generation, failure);
        } finally {
            deferringHistory = false;
        }
    }

    private void deferChange(Change change) {
        if (deferredHistory == null) deferredHistory = new ArrayDeque<>();
        deferredHistory.addLast(change);
    }

    private Throwable appendHistory(Change change, long generation, Throwable failure) {
        if (recording && generation == historyGeneration) failure = addHistory(change, failure);
        while (deferredHistory != null && !deferredHistory.isEmpty()) {
            Change nested = deferredHistory.removeFirst();
            if (recording) failure = addHistory(nested, failure);
        }
        return failure;
    }

    private Throwable addHistory(Change change, Throwable failure) {
        try {
            all.add(change);
        } catch (RuntimeException | Error ex) {
            return ObserverFailures.add(failure, ex);
        }
        return failure;
    }

    public void setModel(VObject model) {
        this.model = model;
    }

    @Override
    public void start() {

        clear();

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
            observeSubtree((VObjectInternal) model, true);
        } else {
            observedObjects.forEach(object -> object.removePropertyChangeListener(objListener));
            observedObjects.clear();
            recursiveCollectionSubscriptions.values().forEach(Subscription::unsubscribe);
            recursiveCollectionSubscriptions.clear();
        }
    }

    private void observeAttachedChild(VObjectInternal owner, Object value) {
        if (recursiveObservationActive && value instanceof VObjectInternalModifiable) {
            var child = (VObjectInternalModifiable) value;
            if (child._vmf_getContainer() == owner && !observedObjects.contains(child) && observedObjects.contains(owner)) {
                observeSubtree(child, true);
            }
        }
    }

    private void stopObservingDetachedChild(VObjectInternal formerOwner, int formerPropertyId,
                                            Set<Object> currentMembers, Object value) {
        if (!(value instanceof VObjectInternalModifiable) || value == model || !observedObjects.contains(value)) return;
        var child = (VObjectInternalModifiable) value;
        VObject parent = child._vmf_getContainer();
        if (parent instanceof VObjectInternal && observedObjects.contains(parent)) {
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
        var pending = new ArrayDeque<VObjectInternal>();
        pending.push(root);
        while (!pending.isEmpty()) {
            VObjectInternal object = pending.pop();
            if (attach) {
                if (!observedObjects.add(object)) continue;
                subscribeCollections(object, true);
                object.addPropertyChangeListener(objListener);
            } else {
                if (!observedObjects.remove(object)) continue;
                unsubscribeCollections(object, recursiveCollectionSubscriptions);
                object.removePropertyChangeListener(objListener);
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
    private void registerChangeListenerNonRecursive(VObject vObj, PropertyChangeListener objListener) {
        subscribeCollections((VObjectInternal) vObj, false);

        VObjectInternal obj = (VObjectInternal) vObj;
        obj.addPropertyChangeListener(objListener);
    }

    @SuppressWarnings("unchecked")
    private void unregisterChangeListenerNonRecursive(VObject vObj, PropertyChangeListener objListener) {
        unsubscribeCollections((VObjectInternal) vObj, localCollectionSubscriptions);

        VObjectInternal obj = (VObjectInternal) vObj;
        obj.removePropertyChangeListener(objListener);
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void subscribeCollections(VObjectInternal owner, boolean recursive) {
        Map<Object, Subscription> subscriptions = recursive ? recursiveCollectionSubscriptions : localCollectionSubscriptions;
        int[] types = owner._vmf_getPropertyTypes();
        for (int id = 0; id < types.length; id++) {
            if (types[id] != -2 && types[id] != VObjectInternal.VMF_TYPE_MAP) continue;
            String name = owner._vmf_getPropertyNames()[id];
            if (types[id] == VObjectInternal.VMF_TYPE_MAP) {
                var map = (VMap<String, Object>) owner._vmf_getPropertyValueById(id);
                subscriptions.put(map, map.addChangeListener(event -> {
                    boolean first = firstDelivery(owner, event);
                    if (needsChange(recursive ? recursivePropertyNames : localPropertyNames, name)) {
                        fireChange(new MapChangeImpl(owner, name, event), first && recording,
                                recursive ? changeListeners : nonRecursiveChangeListeners);
                    }
                }));
            } else {
                var list = (VList<Object>) owner._vmf_getPropertyValueById(id);
                int propertyId = id;
                Subscription subscription = recursive && isContainmentProperty(owner, id)
                        ? list.addChangeListener(event -> observeContainmentListChange(owner, propertyId, list, name, event))
                        : list.addChangeListener(event -> fireListChange(owner, name, event,
                                recursive ? recursivePropertyNames : localPropertyNames,
                                recursive ? changeListeners : nonRecursiveChangeListeners));
                subscriptions.put(list, subscription);
            }
        }
    }

    private void observeContainmentListChange(VObjectInternal owner, int propertyId, VList<Object> list, String name,
                                              CollectionChangeEvent<Object, VList<Object>, VListChange<Object>> event) {
        try {
            fireListChange(owner, name, event, recursivePropertyNames, changeListeners);
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
            if (types[id] == -2 || types[id] == VObjectInternal.VMF_TYPE_MAP) {
                Subscription subscription = subscriptions.remove(owner._vmf_getPropertyValueById(id));
                if (subscription != null) subscription.unsubscribe();
            }
        }
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
        if (needsLocalObservation) registerChangeListenerNonRecursive(model, nonRecursiveObjListener);
        else unregisterChangeListenerNonRecursive(model, nonRecursiveObjListener);
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
