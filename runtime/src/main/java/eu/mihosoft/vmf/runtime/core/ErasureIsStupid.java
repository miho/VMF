package eu.mihosoft.vmf.runtime.core;

/**
 * The {@code ErasureIsStupid} class is an uninstantiable placeholder. We use it to prevent name clashes that occur in
 * methods with identical name but different generic arguments due to type erasure. To fix this limitation, we force a
 * method signature change by adding {@code ErasureIsStupid...} as last method parameter. By doing this, the method's
 * required arguments do not change.
 */
public final class ErasureIsStupid {

    /*
     * The Void class cannot be instantiated.
     */
    private ErasureIsStupid() {throw new AssertionError("Don't instantiate me!");}
}

