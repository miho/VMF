package eu.mihosoft.vmf.conformance.redeclarations.vmfmodel;

import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface PayloadCarrier {
    @IgnoreEquals
    Object getPayload();
}

interface PayloadRecord extends PayloadCarrier { }

interface PayloadList {
    Object[] getItems();
}
