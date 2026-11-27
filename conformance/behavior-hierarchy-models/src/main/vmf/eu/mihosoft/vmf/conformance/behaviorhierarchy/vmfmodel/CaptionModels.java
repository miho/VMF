package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.DelegateTo;

interface PlainCaption { String getLabel(); }

interface MutableCaption extends PlainCaption {
    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.MutableToStringExchange.CaptionBinding") String toString();
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.MutableToStringExchange.CaptionBinding") int calls();
}

interface InheritedCaption extends MutableCaption { }

interface NullCaption {
    String getLabel();
    @Override @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.MutableToStringExchange.NullBinding") String toString();
}

interface UnrelatedCaption { String getLabel(); }
