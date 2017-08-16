package eu.mihosoft.vmf.core;

import java.beans.PropertyChangeListener;

/**
 * Created by miho on 04.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
interface ObservableObject {
    void addPropertyChangeListener(PropertyChangeListener l);
    void removePropertyChangeListener(PropertyChangeListener l);
}