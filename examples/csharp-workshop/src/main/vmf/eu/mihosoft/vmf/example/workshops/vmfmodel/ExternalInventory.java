package eu.mihosoft.vmf.example.workshops.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Multiplicity;

interface ExternalInventory {
    @Annotation(key = "vmf:jackson:rename", value = "warehouse_id")
    String getWarehouseId();

    @Contains
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    @Annotation(key = "vmf:jackson:rename", value = "stock_items")
    StockItem[] getItems();
}

interface StockItem {
    @Annotation(key = "vmf:jackson:rename", value = "part_no")
    String getSku();

    @Annotation(key = "vmf:jackson:rename", value = "available_qty")
    int getQuantity();

    boolean getActive();
}
