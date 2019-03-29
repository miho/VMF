package eu.mihosoft.vmf.runtime.core.internal;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeSupport;

import eu.mihosoft.vmf.runtime.core.VObject;


/**
 * Don't rely on this API. Seriously, <b>don't</b> rely on it!
 */
@Deprecated
public class VMFPropertyChangeSupport extends PropertyChangeSupport {

    private static final long serialVersionUID = -4464120913442618645L;

    private Object source;

    private VMFPropertyChangeSupport(Object sourceBean) {
        super(sourceBean);
        this.source = sourceBean;
    }

    public static VMFPropertyChangeSupport newInstance(VObject vOBject) {
        return new VMFPropertyChangeSupport(vOBject);
    }

    @Override
    public void fireIndexedPropertyChange(String propertyName, int index, Object oldValue, Object newValue) {
        super.fireIndexedPropertyChange(propertyName, index, oldValue, newValue);
    }

    @Override
    public void fireIndexedPropertyChange(String propertyName, int index, boolean oldValue, boolean newValue) {
        super.fireIndexedPropertyChange(propertyName, index, oldValue, newValue);
    }

    @Override
    public void firePropertyChange(PropertyChangeEvent event) {
        super.firePropertyChange(event);
    }

    @Override
    public void fireIndexedPropertyChange(String propertyName, int index, int oldValue, int newValue) {
        super.fireIndexedPropertyChange(propertyName, index, oldValue, newValue);
    }

    @Override
    public void firePropertyChange(String propertyName, Object oldValue, Object newValue) {
        super.firePropertyChange(propertyName, oldValue, newValue);
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
    static final class VMFPropertyChangeEvent extends PropertyChangeEvent {

        private static final long serialVersionUID = 6210683145758719041L;

        private final String evtInfo;

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