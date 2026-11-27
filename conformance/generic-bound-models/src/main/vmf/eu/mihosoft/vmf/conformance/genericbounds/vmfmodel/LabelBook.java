package eu.mihosoft.vmf.conformance.genericbounds.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.List;
import java.util.Set;

@InterfaceOnly
interface LabelView {
    @GetterOnly String getText();
}

@Immutable
@VMFEquals(VMFEquals.EqualsType.ALL)
interface PermanentLabel extends LabelView { }

interface WorkingLabel extends LabelView {
    String getRevision();
}

@InterfaceOnly
interface LabelSlot<T extends LabelView> {
    @Annotation(key = "vmf:jackson:rename", value = "active_label") T getCurrent();
    List<T> getHistory();
    Set<T> getChoices();
}

interface PermanentSlot extends LabelSlot<PermanentLabel> { }

interface WorkingSlot extends LabelSlot<WorkingLabel> { }

interface LabelBook {
    @Contains PermanentSlot getPermanent();
    @Contains WorkingSlot getWorking();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) WorkingLabel[] getOwned();
}
