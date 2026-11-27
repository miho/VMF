package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.QualifiedBindingExchange.Cold.Binding")
interface QualifiedLocal extends LocalLeft, LocalRight {
    @DefaultValue("7") int getLevel();

    int coolLocal();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.QualifiedBindingExchange.Hot.Binding")
    int warmLocal();
}
