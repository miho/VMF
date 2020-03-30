package eu.mihosoft.vmf.runtime.core;

public interface Behavior<T extends VObject> {
    DelegatedBehavior<T> get();
    void set(DelegatedBehavior<T> delegate);
}
