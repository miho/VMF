package eu.mihosoft.vmf.conformance.immutableancestry.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.PropertyOrder;

@InterfaceOnly
interface LeadingCaption {
    @GetterOnly @PropertyOrder(index = 0)
    @Annotation(key = "vmf:jackson:rename", value = "display_caption")
    String getCaption();
}

@Immutable
@DelegateTo(className = "eu.mihosoft.vmf.conformance.immutableancestry.ImmutableParentOrderExchange.Binding")
interface ValueOrigin {
    @PropertyOrder(index = 1) String getSource();
    String describe();
    int calls();
}

@Immutable
interface TraitFirstValue extends LeadingCaption, ValueOrigin {
    @PropertyOrder(index = 2) int getQuantity();
}

@Immutable
interface BaseFirstValue extends ValueOrigin, LeadingCaption {
    @PropertyOrder(index = 2) int getQuantity();
}

interface OrderedParentsEnvelope {
    ValueOrigin getEarlyBase();
    LeadingCaption getEarlyTrait();

    ValueOrigin getControlBase();
    LeadingCaption getControlTrait();
}
