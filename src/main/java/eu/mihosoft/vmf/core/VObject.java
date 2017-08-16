package eu.mihosoft.vmf.core;

/**
 * Created by miho on 04.01.2017.
 * 
 * @author Michael Hoffer <info@michaelhoffer.de>
 */
interface VObject<T> extends ObservableObject {
    T newInstance();
}
