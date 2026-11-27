package eu.mihosoft.vmf.conformance.genericbounds.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

@InterfaceOnly interface QualifiedView<T> extends LabelView {
    @GetterOnly T getQualifier();
}

@InterfaceOnly interface StringQualifiedView extends QualifiedView<String> { }

interface WorkingQualifiedLabel extends StringQualifiedView { }

@Immutable interface PermanentQualifiedLabel extends StringQualifiedView { }

interface NamedAppliedSelection extends LabelSlot<StringQualifiedView> {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) WorkingQualifiedLabel[] getOwned();
}

@InterfaceOnly interface MutableStringQualifiedView extends QualifiedView<String> { }

interface MutableQualifiedLabel extends MutableStringQualifiedView { }

interface NamedMutableAppliedSelection extends LabelSlot<MutableStringQualifiedView> {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) MutableQualifiedLabel[] getOwned();
}
