package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

interface QualifiedCold {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.QualifiedBindingExchange.Cold.Binding")
    int cool();
}

@InterfaceOnly
interface QualifiedColdRole {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.QualifiedBindingExchange.Cold.Binding")
    int coolAgain();
}

@InterfaceOnly
interface QualifiedHot {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.QualifiedBindingExchange.Hot.Binding")
    int warm();
}

@InterfaceOnly
interface QualifiedHotRole {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.QualifiedBindingExchange.Hot.Binding")
    int warmAgain();
}

interface QualifiedShared extends QualifiedCold, QualifiedColdRole, QualifiedHot, QualifiedHotRole {
    String getName();
}

interface QualifiedBindingArchive {
    @Contains QualifiedLocal getLocal();
    @Contains QualifiedShared getShared();
    QualifiedShared getSelected();
}
