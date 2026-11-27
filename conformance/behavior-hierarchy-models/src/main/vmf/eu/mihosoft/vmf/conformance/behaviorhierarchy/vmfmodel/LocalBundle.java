package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.LocalBindingExchange.SharedDelegate")
interface LocalLeft {
    @DefaultValue("11") int getLeftValue();
    int left();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.LocalBindingExchange.SharedDelegate")
interface LocalRight {
    @DefaultValue("22") int getRightValue();
    int right();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.LocalBindingExchange.ColdDelegate")
interface LocalComposite extends LocalLeft, LocalRight {
    @DefaultValue("33") int getLocalValue();

    int cool();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.LocalBindingExchange.HotDelegate")
    int heat();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.LocalBindingExchange.HotDelegate")
    int warm();

    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.LocalBindingExchange.SharedDelegate")
    int joined();
}

interface LocalCompositeChild extends LocalComposite { }

interface LocalBundle {
    @Contains LocalLeft[] getItems();
    LocalRight getSelected();
}
