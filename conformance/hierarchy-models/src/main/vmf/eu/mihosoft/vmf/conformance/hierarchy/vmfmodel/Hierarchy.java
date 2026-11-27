package eu.mihosoft.vmf.conformance.hierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;

@InterfaceOnly
interface NamedItem {
    @Annotation(key = "vmf:jackson:rename", value = "label")
    String getName();
}

@InterfaceOnly
interface RatedItem {
    @Annotation(key = "vmf:jackson:rename", value = "label")
    String getName();
    int getRating();
}

interface CatalogItem extends NamedItem, RatedItem {
    String getCode();
}

@InterfaceOnly
interface CatalogLabel {
    String getTitle();
}

@InterfaceOnly
interface ItemIndex {
    @Contains
    @Keyed(by = "name")
    CatalogItem[] getItems();

    RatedItem getSelected();
}

interface TraitCatalog extends CatalogLabel, ItemIndex { }

@InterfaceOnly
interface PartSpec {
    String getName();
}

interface ConcretePart extends PartSpec {
    int getNumber();
}

@InterfaceOnly
interface PartGroup<T extends PartSpec> {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    T[] getParts();

    T getFeatured();
}

@InterfaceOnly
interface LeftParts<T extends PartSpec> extends PartGroup<T> {
    boolean isHighlighted();
}

@InterfaceOnly
interface RightParts<T extends PartSpec> extends PartGroup<T> {
    String getSection();
}

interface PartsCatalog extends LeftParts<ConcretePart>, RightParts<ConcretePart> { }

@InterfaceOnly
interface OpenParts<X extends PartSpec> extends LeftParts<X>, RightParts<X> { }

interface OpenPartsCatalog extends OpenParts<ConcretePart> { }

interface WorkbenchLabel { String getLabel(); }

interface PartsWorkbench extends WorkbenchLabel, OpenParts<ConcretePart> { }

interface ChoiceItem { String getName(); }

interface RankedChoice extends ChoiceItem { int getRank(); }

interface VersionedChoice { int getVersion(); }

@InterfaceOnly
interface SecondaryChoice<T extends ChoiceItem> {
    @Annotation(key = "vmf:jackson:rename", value = "chosen")
    T getSelected();
    boolean isLocked();
}

@InterfaceOnly
interface OpenChoice<T extends ChoiceItem> extends VersionedChoice, SecondaryChoice<T> { }

interface ItemSelection extends OpenChoice<ChoiceItem> { }

interface RankedSelection extends OpenChoice<RankedChoice> {
    @Contains RankedChoice getEntry();
}

interface BenchTool { int getSize(); }

interface CalibratedTool extends BenchTool { int getCalibration(); }

@InterfaceOnly
interface ToolBackup<U extends BenchTool> { U getBackup(); }

@InterfaceOnly
interface DualChoice<T extends ChoiceItem, U extends BenchTool> extends SecondaryChoice<T>, ToolBackup<U> { }

interface RankedDualChoice extends DualChoice<RankedChoice, CalibratedTool> { }

@InterfaceOnly
interface ChoiceQueue<E extends ChoiceItem> { E[] getQueue(); }

@InterfaceOnly
interface OpenChoiceQueue<T extends ChoiceItem> extends VersionedChoice, ChoiceQueue<T> { }

interface RankedChoiceQueue extends OpenChoiceQueue<RankedChoice> {
    @Contains RankedChoice getEntry();
}
