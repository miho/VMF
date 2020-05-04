package eu.mihosoft.vmf.runtime.core;

/**
 * Delegation base class for invoking custom implementations. VMF objects do not support
 * custom extensions by manipulating their implementation code. However, custom behavior can be realized be defining
 * delegation classes that extend this class.
 *
 * <p>Created by miho on 04.05.2020.</p>
 *
 * @author Michael Hoffer <info@michaelhoffer.de>
 * @see <a href="https://github.com/miho/VMF-Tutorials/blob/master/VMF-Tutorial-08/README.md">Tutorial on Custom Behavior & Delegation</a>
 * @see DelegatedBehavior
 */
public class DelegatedBehaviorBase<T extends VObject> implements DelegatedBehavior<T> {
    private T caller;

    /**
     * Returns the caller currently associated with this behavior.
     * @return the caller currently associated with this behavior or {@code null} if no such caller exists
     */
    public T getCaller() {
        return this.caller;
    }

    @Override
    public void setCaller(T caller) {
        this.caller = caller;
    }
}
