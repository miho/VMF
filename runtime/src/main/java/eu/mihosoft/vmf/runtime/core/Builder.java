package eu.mihosoft.vmf.runtime.core;

/**
 *
 */
public interface Builder {
    default VObject build() {
        throw new UnsupportedOperationException("Cannot build interface-only type");
    }
}
