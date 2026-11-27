package eu.mihosoft.vmf.conformance.values.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;

interface KeyedInventoryBase {
    @Contains
    @Keyed(by = "sku")
    @Annotation(key = "vmf:jackson:rename", value = "items")
    KeyedItem[] getProducts();
}

interface KeyedInventory extends KeyedInventoryBase {
    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    KeyedPart[] getParts();

    @Contains
    @Keyed(by = "part")
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    KeyedAssignment[] getAssignments();
}

interface KeyedItemBase {
    @Annotation(key = "vmf:jackson:rename", value = "sku_code")
    String getSku();
}

interface KeyedItem extends KeyedItemBase {
    int getQuantity();
}

interface KeyedPart {
    String getName();
}

interface KeyedAssignment {
    KeyedPart getPart();
    int getCount();
}
