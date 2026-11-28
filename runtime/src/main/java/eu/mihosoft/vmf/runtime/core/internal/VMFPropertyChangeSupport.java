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

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeListenerProxy;
import java.beans.PropertyChangeSupport;

import eu.mihosoft.vmf.runtime.core.VObject;


/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 */
@Deprecated
public class VMFPropertyChangeSupport extends PropertyChangeSupport {

    private static final long serialVersionUID = -4464120913442618645L;

    interface PreparingListener extends PropertyChangeListener {
        void prepare(PropertyChangeEvent event);
    }

    private Object source;
    private transient volatile PropertyChangeListener[] dispatchListeners;

    private VMFPropertyChangeSupport(Object sourceBean) {
        super(sourceBean);
        this.source = sourceBean;
    }

    public static VMFPropertyChangeSupport newInstance(VObject vOBject) {
        return new VMFPropertyChangeSupport(vOBject);
    }

    private static final ClassValue<Boolean> IDENTITY_EQUALITY = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("equals", Object.class).getDeclaringClass() == Object.class;
            } catch (ReflectiveOperationException | SecurityException ex) {
                return false;
            }
        }
    };

    /** Named listeners and custom equality need the normal JavaBeans lookup. */
    public static boolean usesIdentityLookup(PropertyChangeListener listener) {
        return !(listener instanceof PropertyChangeListenerProxy) && IDENTITY_EQUALITY.get(listener.getClass());
    }

    /**
     * Custom external equality can update the listeners before JavaBeans takes its
     * dispatch snapshot. Use the normal support for such values.
     */
    public static boolean canNotifySingleListener(Object oldValue, Object newValue) {
        return oldValue == null || newValue == null || oldValue instanceof VObject || newValue instanceof VObject
                || oldValue instanceof Integer || oldValue instanceof String || oldValue instanceof Boolean
                || oldValue instanceof Long || oldValue instanceof Double || oldValue instanceof Float
                || oldValue instanceof Short || oldValue instanceof Byte || oldValue instanceof Character
                || oldValue instanceof Enum<?>;
    }

    /** Fires the same VMF event when one ordinary listener is registered. */
    public static void fireToSingleListener(VObject source, PropertyChangeListener listener, String propertyName,
                                            Object oldValue, Object newValue, String eventInfo) {
        boolean changed = oldValue instanceof VObject || newValue instanceof VObject
                ? oldValue != newValue
                : oldValue == null || newValue == null || !oldValue.equals(newValue);
        if (changed) {
            var event = VMFPropertyChangeEvent.newInstance(source, propertyName, oldValue, newValue, eventInfo);
            prepare(listener, event);
            listener.propertyChange(event);
        }
    }

    @Override
    public void fireIndexedPropertyChange(String propertyName, int index, Object oldValue, Object newValue) {
        super.fireIndexedPropertyChange(propertyName, index, oldValue, newValue);
    }

    @Override
    public void fireIndexedPropertyChange(String propertyName, int index, boolean oldValue, boolean newValue) {
        super.fireIndexedPropertyChange(propertyName, index, oldValue, newValue);
    }

    /**
     * Fires the specified event. Model objects (instances of {@link VObject}) are
     * compared by identity: if a different but structurally equal object replaces
     * the current one, the listeners are notified. (The JDK implementation
     * suppresses events whose old and new value are {@code equals()}, which is
     * kept for all other values.)
     */
    @Override
    public void firePropertyChange(PropertyChangeEvent event) {
        Object oldValue = event.getOldValue();
        Object newValue = event.getNewValue();

        boolean modelValue = oldValue instanceof VObject || newValue instanceof VObject;
        if (modelValue ? oldValue == newValue : oldValue != null && newValue != null && oldValue.equals(newValue)) return;
        fireToListeners(event, modelValue);
    }

    private PropertyChangeListener[] dispatchSnapshot() {
        PropertyChangeListener[] snapshot = dispatchListeners;
        if (snapshot != null) return snapshot;
        synchronized (this) {
            if (dispatchListeners == null) dispatchListeners = super.getPropertyChangeListeners();
            return dispatchListeners;
        }
    }

    @Override
    public synchronized void addPropertyChangeListener(PropertyChangeListener listener) {
        super.addPropertyChangeListener(listener);
        dispatchListeners = null;
    }

    @Override
    public synchronized void removePropertyChangeListener(PropertyChangeListener listener) {
        super.removePropertyChangeListener(listener);
        dispatchListeners = null;
    }

    @Override
    public synchronized void addPropertyChangeListener(String name, PropertyChangeListener listener) {
        super.addPropertyChangeListener(name, listener);
        dispatchListeners = null;
    }

    @Override
    public synchronized void removePropertyChangeListener(String name, PropertyChangeListener listener) {
        super.removePropertyChangeListener(name, listener);
        dispatchListeners = null;
    }

    private void fireToListeners(PropertyChangeEvent event, boolean modelValue) {
        String propertyName = event.getPropertyName();
        var snapshot = dispatchSnapshot();
        for (PropertyChangeListener listener : snapshot) prepare(listener, event);
        Throwable failure = null;
        for (PropertyChangeListener listener : snapshot) {
            if (listener instanceof PropertyChangeListenerProxy) {
                PropertyChangeListenerProxy proxy = (PropertyChangeListenerProxy) listener;
                if (propertyName == null ? !modelValue : !propertyName.equals(proxy.getPropertyName())) continue;
            }
            try {
                listener.propertyChange(event);
            } catch (RuntimeException | Error ex) {
                failure = ObserverFailures.add(failure, ex);
            }
        }
        ObserverFailures.rethrow(failure);
    }

    private static void prepare(PropertyChangeListener listener, PropertyChangeEvent event) {
        if (listener instanceof PreparingListener) ((PreparingListener) listener).prepare(event);
    }

    @Override
    public void fireIndexedPropertyChange(String propertyName, int index, int oldValue, int newValue) {
        super.fireIndexedPropertyChange(propertyName, index, oldValue, newValue);
    }

    @Override
    public void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
        // routed through the event method so that model objects are compared by identity
        firePropertyChange(new PropertyChangeEvent(source, propertyName, oldValue, newValue));
    }

    @Override
    public void firePropertyChange(String propertyName, boolean oldValue, boolean newValue) {
        super.firePropertyChange(propertyName, oldValue, newValue);
    }

    @Override
    public void firePropertyChange(String propertyName, int oldValue, int newValue) {
        super.firePropertyChange(propertyName, oldValue, newValue);
    }

    /**
     * Fires a property change event including event info. Used internally, do not rely on this API!
     */
    public void firePropertyChange(String propertyName, Object oldValue, Object newValue, String evtInfo) {
        firePropertyChange(VMFPropertyChangeEvent.newInstance(source, propertyName, oldValue, newValue, evtInfo));
    }

    /**
     * Extended version of a property change event. Includes a event-info. Do not rely on this API.
     */
    @Deprecated
    static final class VMFPropertyChangeEvent extends PropertyChangeEvent implements DeliveryOrder.Stamped {

        private static final long serialVersionUID = 6210683145758719041L;

        private final String evtInfo;
        private transient long deliveryStamp;

        @Override public long deliveryStamp() { return deliveryStamp; }
        @Override public void deliveryStamp(long stamp) { deliveryStamp = stamp; }

        private VMFPropertyChangeEvent(Object source, String propertyName,
            Object oldValue, Object newValue, String evtInfo) {
            super(source, propertyName, oldValue, newValue);
            this.evtInfo = evtInfo;
        }

        public static VMFPropertyChangeEvent newInstance(Object source,
            String propertyName,
            Object oldValue, Object newValue, String evtInfo
        ) {
             return new VMFPropertyChangeEvent(source, propertyName, oldValue, newValue, evtInfo);
        }
        
        /**
         * Returns the event info of this event object.
         */
        public String getEventInfo() {
            return this.evtInfo;
        }

    }

}
