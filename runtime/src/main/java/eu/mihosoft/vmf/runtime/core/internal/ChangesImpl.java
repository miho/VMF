/*
 * Copyright 2017-2019 Michael Hoffer <info@michaelhoffer.de>. All rights reserved.
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
import eu.mihosoft.vcollections.VMappedList;
import eu.mihosoft.vmf.runtime.core.*;
import vjavax.observer.Subscription;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 *
 * @author Michael Hoffer (info@michaelhoffer.de)
 */
@Deprecated
public class ChangesImpl implements Changes {

    private final VList<ChangeListener> changeListeners = VList.newInstance(new ArrayList<>());
    private final VList<ChangeListener> nonRecursiveChangeListeners = VList.newInstance(new ArrayList<>());


    private final VList<Change> all = VList.newInstance(new ArrayList<>());
    private final VList<Change> unmodifiableAll
            = VMappedList.newInstance(all, (e) -> e,
                    (e) -> {
                        throw new UnsupportedOperationException("List modification not supported!");
                    });
    private final VList<Transaction> transactions
            = VList.newInstance(new ArrayList<>());
    private final VList<Transaction> unmodifiableTransactions
            = VMappedList.newInstance(transactions, (e) -> e,
                    (e) -> {
                        throw new UnsupportedOperationException("List modification not supported!");
                    });
    private int currentTransactionStartIndex = 0;

    private VObject model;

    private final List<Subscription> subscriptions = new ArrayList<>();
    private final List<Subscription> nonRecursiveSubscriptions = new ArrayList<>();
    private final Map<Object, Subscription> listSubscriptions = new IdentityHashMap<>();
    private final Map<Object, Subscription> nonRecursiveListSubscriptions = new IdentityHashMap<>();

    private final PropertyChangeListener objListener;
    private final PropertyChangeListener nonRecursiveObjListener;

    private boolean modelVersioningEnabled;
    private Subscription modelVersioningSubscription;

    private boolean recording;

    private long timestamp;
    private final AtomicLong modelVersionNumber = new AtomicLong(0);

    private ModelVersion modelVersion = new ModelVersionImpl(System.currentTimeMillis(), 0);

    private boolean isOldContained(PropertyChangeEvent evt) {
        if (evt.getOldValue() instanceof VObject) {

            if (evt.getSource() instanceof VObject) {
                VObject source = (VObject) evt.getSource();

                VObjectInternal sourceInternal = (VObjectInternal) source;
                int changedId = sourceInternal._vmf_getPropertyIdByName(evt.getPropertyName());

                for (int propId : sourceInternal._vmf_getChildrenIndices()) {
                    if (changedId == propId) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isNewContained(PropertyChangeEvent evt) {
        if (evt.getNewValue() instanceof VObject) {
            if (evt.getSource() instanceof VObject) {
                VObject source = (VObject) evt.getSource();

                VObjectInternal sourceInternal = (VObjectInternal) source;
                int changedId = sourceInternal._vmf_getPropertyIdByName(evt.getPropertyName());

                for (int propId : sourceInternal._vmf_getChildrenIndices()) {
                    if (changedId == propId) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @SuppressWarnings({"deprecation"})
    public ChangesImpl() {
        objListener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {

                String evtInfo;

                if(evt instanceof eu.mihosoft.vmf.runtime.core.internal.VMFPropertyChangeSupport.VMFPropertyChangeEvent) {
                    evtInfo = ((eu.mihosoft.vmf.runtime.core.internal.VMFPropertyChangeSupport.VMFPropertyChangeEvent)evt).getEventInfo();
                } else {
                    evtInfo = "";
                }

                Change c = new PropChangeImpl((VObject) evt.getSource(), evt.getPropertyName(),
                        evt.getOldValue(), evt.getNewValue(), evtInfo);
     
                fireChange(c,evt);

                if (isNewContained(evt)) {
                    VObject newObjectToObserve = (VObject) evt.getNewValue();
                    registerChangeListener(newObjectToObserve, this);
                }
                    
                if(isOldContained(evt)) {
                    VObject objectToRemoveFromObservation = (VObject) evt.getOldValue();
                    unregisterChangeListener(objectToRemoveFromObservation, this);
                }
                
            }
        };

        AtomicBoolean recursiveRegistered = new AtomicBoolean(false);
        changeListeners.addChangeListener((evt) -> {
            if (changeListeners.isEmpty() && !recording && recursiveRegistered.get()) {
                // System.out.println("rc-unregister");
                unregisterChangeListener(model, objListener);
                recursiveRegistered.set(false);
            } else if (!changeListeners.isEmpty() && !recursiveRegistered.get()) {
                // System.out.println("rc-register");
                registerChangeListener(model, objListener);
                recursiveRegistered.set(true);
            }
        });

        nonRecursiveObjListener = new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {

                String evtInfo;

                if(evt instanceof eu.mihosoft.vmf.runtime.core.internal.VMFPropertyChangeSupport.VMFPropertyChangeEvent) {
                    evtInfo = ((eu.mihosoft.vmf.runtime.core.internal.VMFPropertyChangeSupport.VMFPropertyChangeEvent)evt).getEventInfo();
                } else {
                    evtInfo = "";
                }

                Change c = new PropChangeImpl((VObject) evt.getSource(), evt.getPropertyName(),
                        evt.getOldValue(), evt.getNewValue(), evtInfo);

                fireChangeForNonRecursive(c, evt);
            }
        };

        AtomicBoolean nonrecursiveRegistered = new AtomicBoolean(false);
        nonRecursiveChangeListeners.addChangeListener((evt) -> {
            if (nonRecursiveChangeListeners.isEmpty() && !recording && nonrecursiveRegistered.get()) {
                // System.out.println("nr-unregister");
                unregisterChangeListenerNonRecursive(model, nonRecursiveObjListener);
                nonRecursiveSubscriptions.forEach(s->s.unsubscribe());
                nonrecursiveRegistered.set(false);
            } else if (!nonRecursiveChangeListeners.isEmpty() && !nonrecursiveRegistered.get()) {
                // System.out.println("nr-register");
                registerChangeListenerNonRecursive(model, nonRecursiveObjListener);
                nonrecursiveRegistered.set(true);
            }
        });


    }

    private Object lastFiredEvt;

    private void fireChange(Change c, Object evt) {

        for (ChangeListener cl : changeListeners) {
            cl.onChange(c);
        }

        // if (recording)all.add(c);
        if (recording && lastFiredEvt !=evt/*&& nonRecursiveChangeListeners.isEmpty()*/ ) {

            // check whether the change belongs to a parent property of a
            // contained object (opposite)
            // -> in this case we omit the change from recorded changes
            VObjectInternal modelInternal = (VObjectInternal) c.object();
            int pIndex = modelInternal._vmf_getPropertyIdByName(c.propertyName());
            boolean foundParent = false;
            for(int idx : modelInternal._vmf_getParentIndices()) {

                if (pIndex == idx) {
                    foundParent = true;
                    break;
                }
            }

            if(!foundParent && !ChangeInternal.isCrossRefChange(c)) {
                all.add(c);
            }
        }

        lastFiredEvt = evt;
    }

    private void fireChangeForNonRecursive(Change c, Object evt) {

        for (ChangeListener cl : nonRecursiveChangeListeners) {
            cl.onChange(c);
        }

        if (recording && lastFiredEvt !=evt) {

            // check whether the change belongs to a parent property of a
            // contained object (opposite)
            // -> in this case we omit the change from recorded changes
            VObjectInternal modelInternal = (VObjectInternal) c.object();
            int pIndex = modelInternal._vmf_getPropertyIdByName(c.propertyName());
            boolean foundParent = false;
            for(int idx : modelInternal._vmf_getParentIndices()) {
                if (pIndex == idx) {
                    foundParent = true;
                    break;
                }
            }
            
            if(!foundParent && !ChangeInternal.isCrossRefChange(c)) {
                all.add(c);
            }
        }

        lastFiredEvt = evt;
    }

    public void setModel(VObject model) {
        this.model = model;
    }

    @Override
    public void start() {

        clear();

        recording = true;

        registerChangeListener(model, objListener);

        enableModelVersioning();
    }

    @SuppressWarnings("unchecked")
    private void registerChangeListener(VObject vObj, PropertyChangeListener objListener) {

        Iterator<VObject> it = vObj.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        while (it.hasNext()) {

            VObjectInternal obj = (VObjectInternal) it.next();

            removeListListenersFromPropertiesOf(obj, objListener);
            addListListenersToPropertiesOf(obj, objListener);

            obj.removePropertyChangeListener(objListener);
            obj.addPropertyChangeListener(objListener);

            subscriptions.add(() -> obj.removePropertyChangeListener(objListener));
        }
    }

    @SuppressWarnings("unchecked")
    private void unregisterChangeListener(VObject vObj, PropertyChangeListener objListener) {

        Iterator<VObject> it = vObj.vmf().content().iterator(VIterator.IterationStrategy.CONTAINMENT_TREE);
        while (it.hasNext()) {

            VObjectInternal obj = (VObjectInternal) it.next();

            removeListListenersFromPropertiesOf(obj, objListener);
            obj.removePropertyChangeListener(objListener);
        }
    }

    @SuppressWarnings("unchecked")
    private void registerChangeListenerNonRecursive(VObject vObj, PropertyChangeListener objListener) {
        addNonRecursiveListListeners(vObj, objListener);

        VObjectInternal obj = (VObjectInternal) vObj;
        obj.addPropertyChangeListener(objListener);
    }

    @SuppressWarnings("unchecked")
    private void unregisterChangeListenerNonRecursive(VObject vObj, PropertyChangeListener objListener) {
        removeNonRecursiveListListeners(vObj, objListener);

        VObjectInternal obj = (VObjectInternal) vObj;
        obj.removePropertyChangeListener(objListener);
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void addNonRecursiveListListeners(VObject object, PropertyChangeListener objListener) {
        VObjectInternal internalModel = (VObjectInternal) object;
        for (int i = 0; i < internalModel._vmf_getPropertyTypes().length; i++) {
            int type = internalModel._vmf_getPropertyTypes()[i];
            if (type == -2) {
                String propName = internalModel._vmf_getPropertyNames()[i];

                VList<Object> list = (VList<Object>) internalModel._vmf_getPropertyValueById(i);

                Subscription subscription = list.addChangeListener(
                        (evt) -> {
                            Change c = new ListChangeImpl(object, propName, evt);

                            fireChangeForNonRecursive(c, evt);
                        }
                );

                nonRecursiveSubscriptions.add(subscription);
                nonRecursiveListSubscriptions.put(list, subscription);
            }
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void addListListenersToPropertiesOf(VObject object, PropertyChangeListener objListener) {
        VObjectInternal internalModel = (VObjectInternal) object;
        for (int i = 0; i < internalModel._vmf_getPropertyTypes().length; i++) {
            int type = internalModel._vmf_getPropertyTypes()[i];
            if (type == -2) {
                String propName = internalModel._vmf_getPropertyNames()[i];

                VList<Object> list = (VList<Object>) internalModel._vmf_getPropertyValueById(i);

                Subscription subscription = list.addChangeListener(
                        (evt) -> {
                            
                            Change c = new ListChangeImpl(object, propName, evt);

                            fireChange(c, evt);

                            evt.added().elements().stream().filter(
                                    e -> e instanceof VObjectInternal).
                            map(e -> (VObjectInternal) e).forEach(v
                            -> {

                                v.removePropertyChangeListener(objListener);
                                registerChangeListener(v, objListener);
                                subscriptions.add(
                                        () -> v.removePropertyChangeListener(objListener));
                            });

                            evt.removed().elements().stream().
                            filter(e -> e instanceof VObject).
                            map(e -> (VObject) e).
                            forEach(v -> unregisterChangeListener(v, objListener));
                        }
                );

                subscriptions.add(subscription);
                listSubscriptions.put(list, subscription);
            }
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void removeListListenersFromPropertiesOf(VObject object, PropertyChangeListener objListener) {
        VObjectInternal internalModel = (VObjectInternal) object;
        for (int i = 0; i < internalModel._vmf_getPropertyTypes().length; i++) {
            int type = internalModel._vmf_getPropertyTypes()[i];
            if (type == -2) {
                String propName = internalModel._vmf_getPropertyNames()[i];

                VList<Object> list = (VList<Object>) internalModel._vmf_getPropertyValueById(i);

                if (listSubscriptions.containsKey(list)) {
                    Subscription listSubscription = listSubscriptions.get(list);
                    listSubscriptions.remove(list);
                    listSubscription.unsubscribe();
                }
            }
        }
    }

    @SuppressWarnings({"deprecation", "unchecked"})
    private void removeNonRecursiveListListeners(VObject object, PropertyChangeListener objListener) {
        VObjectInternal internalModel = (VObjectInternal) object;
        for (int i = 0; i < internalModel._vmf_getPropertyTypes().length; i++) {
            int type = internalModel._vmf_getPropertyTypes()[i];
            if (type == -2) {
                VList<Object> list = (VList<Object>) internalModel._vmf_getPropertyValueById(i);

                if (nonRecursiveListSubscriptions.containsKey(list)) {
                    Subscription listSubscription = nonRecursiveListSubscriptions.get(list);
                    nonRecursiveListSubscriptions.remove(list);
                    listSubscription.unsubscribe();
                }
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
            this.changes = changes;

            // try to reduce property changes
            // TODO 14.10.2019
        }

        @Override
        public List<Change> changes() {
            return this.changes;
        }

        @Override
        public boolean isUndoable() {

            for (Change c : changes) {
                if (!c.isUndoable()) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public void undo() {
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
        if (currentTransactionStartIndex < unmodifiableAll.size()) {
            transactions.add(new TransactionImpl(
                    unmodifiableAll.subList(
                            currentTransactionStartIndex, all.size()
                    ))
            );
            currentTransactionStartIndex = unmodifiableAll.size();
        }
    }

    @Override
    public void stop() {
        if (currentTransactionStartIndex < all.size()) {
            publishTransaction();
        }

        subscriptions.forEach(s -> s.unsubscribe());
        subscriptions.clear();

        recording = false;

        unregisterChangeListener(model, objListener);

        disableModelVersioning();
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
        all.clear();
        transactions.clear();
    }

    @Override
    public Subscription addListener(ChangeListener l) {

        changeListeners.add(l);

        return () -> changeListeners.remove(l);
    }

    @Override
    public Subscription addListener(ChangeListener l, boolean recursive) {

        if(recursive) {
            return addListener(l);
        } else {
            nonRecursiveChangeListeners.add(l);
            return () -> nonRecursiveChangeListeners.remove(l);
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
