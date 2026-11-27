package eu.mihosoft.vmf.conformance.nominalbounds.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.List;
import java.util.Set;

@InterfaceOnly
interface LabelNode { String getId(); String getText(); }

interface Leaf extends LabelNode { }

interface TaggedLeaf extends Leaf { String getTag(); }

interface Other extends LabelNode { }

@InterfaceOnly
interface Related<B extends LabelNode, S extends B> {
    B getBase();
    S getSelected();
    List<S> getHistory();
}

@InterfaceOnly
interface Forward<C extends LabelNode, D extends C> extends Related<C, D> { }

interface Relations extends Forward<LabelNode, Leaf> { }

@InterfaceOnly
interface SecondaryRelations extends Related<LabelNode, Leaf> { }

interface RecordBase { }

interface ComposedRelations extends RecordBase, SecondaryRelations { }

@InterfaceOnly
interface LabelContract { @GetterOnly String getText(); }

interface DetailedLabel extends LabelContract { String getNote(); }

@InterfaceOnly
interface TraitRelations<B extends LabelContract, S extends B> {
    S getSelected();
    List<S> getHistory();
}

@InterfaceOnly
interface ForwardTrait<C extends LabelContract, D extends C> extends TraitRelations<C, D> { }

interface DetailedRelations extends ForwardTrait<DetailedLabel, DetailedLabel> { }

@Immutable
@VMFEquals(VMFEquals.EqualsType.ALL)
interface FrozenLabel {
    @IgnoreEquals String getId();
    String getText();
}

@InterfaceOnly
interface Shelf<T extends FrozenLabel> {
    T getSelected();
    List<T> getHistory();
    Set<T> getPinned();
}

interface Review extends Shelf<FrozenLabel> { }

interface Library {
    @Contains Relations getRelations();
    @Contains Review getReview();
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) LabelNode[] getNodes();
}
