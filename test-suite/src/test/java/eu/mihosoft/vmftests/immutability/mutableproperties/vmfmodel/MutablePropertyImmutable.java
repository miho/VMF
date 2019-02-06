package eu.mihosoft.vmftests.immutability.mutableproperties.vmfmodel;

import eu.mihosoft.vmf.core.Immutable;

@Immutable
public interface MutablePropertyImmutable {
    MutableProperty getProperty();
}