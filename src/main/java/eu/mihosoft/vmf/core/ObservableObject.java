package eu.mihosoft.vmf.core;

import java.beans.PropertyChangeListener;

/**
 * Created by miho on 04.01.2017.
 */
interface ObservableObject {
    void addPropertyChangeListener(PropertyChangeListener l);
    void removePropertyChangeListener(PropertyChangeListener l);
}