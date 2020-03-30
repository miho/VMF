package eu.mihosoft.vmf.runtime.core;

public interface Behavior<T extends VObject> {
    DelegatedBehavior<T> getBehavior();
    void setBehavior(DelegatedBehavior<T> delegate);
}
