package eu.mihosoft.vmf.conformance.covariance.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Multiplicity;

interface ReferenceShowcase {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) PickableItem[] getItems();
    @Contains ExactPick getExact();
    @Contains NarrowPick getNarrow();
    @Contains ReaffirmedPick getReaffirmed();
    FrozenChoice getFrozen();
    FrozenTraitChoice getTraitFrozen();
}

interface PickableItem { String getCode(); }

interface ClassifiedItem extends PickableItem { String getCategory(); }

@InterfaceOnly
interface PickTrait {
    @GetterOnly @Annotation(key = "vmf:jackson:rename", value = "old_pick")
    PickableItem getCurrent();
}

interface ExactPick extends PickTrait { }

interface NarrowPick extends PickTrait {
    @Override @Annotation(key = "vmf:jackson:rename", value = "preferred") ClassifiedItem getCurrent();
}

@InterfaceOnly
interface ClassifiedPickTrait extends PickTrait {
    @Override @GetterOnly ClassifiedItem getCurrent();
}

interface ReaffirmedPick extends ClassifiedPickTrait {
    @Override @Annotation(key = "vmf:jackson:rename", value = "reaffirmed_pick") ClassifiedItem getCurrent();
}

@Immutable
interface ValueTag { String getTitle(); }

@Immutable
interface ColorTag extends ValueTag { String getColor(); }

@Immutable
interface FrozenChoice {
    @Annotation(key = "vmf:jackson:rename", value = "old_tag") ValueTag getCurrent();
}

@Immutable
interface FrozenColorChoice extends FrozenChoice {
    @Override @Annotation(key = "vmf:jackson:rename", value = "color_tag") ColorTag getCurrent();
}

@InterfaceOnly
interface FrozenPickTrait { @GetterOnly ValueTag getCurrent(); }

@Immutable
interface FrozenTraitChoice extends FrozenPickTrait {
    @Override @Annotation(key = "vmf:jackson:rename", value = "trait_color") ColorTag getCurrent();
}
