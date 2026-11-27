package eu.mihosoft.vmf.conformance.generickeyed.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;

@InterfaceOnly
interface ItemKey {
    @Annotation(key = "vmf:jackson:rename", value = "stock_code")
    String getSku();
}

interface CatalogueItem extends ItemKey {
    String getIdentity();
}

interface LabelledItem extends CatalogueItem {
    String getLabel();
}

@InterfaceOnly
interface ItemCatalogue<E extends CatalogueItem> {
    @Contains @Keyed(by = "sku")
    @Annotation(key = "vmf:jackson:rename", value = "concrete_entries")
    E[] getItems();
}

interface InventoryCatalogue extends ItemCatalogue<CatalogueItem> { }

interface LabelledCatalogue extends ItemCatalogue<LabelledItem> { }
