package eu.mihosoft.vmf.conformance.keyedredeclaration.vmfmodel;

import eu.mihosoft.vmf.core.Annotation;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;

@InterfaceOnly interface HasSku<S> { S getSku(); }

interface StockEntry extends HasSku<String> { String getId(); }

@InterfaceOnly interface StockOwner {
    @Contains @Keyed(by = "sku")
    @Annotation(key = "vmf:jackson:rename", value = "stock")
    StockEntry[] getItems();
}

@InterfaceOnly interface BackupOwner {
    @Contains @Keyed(by = "sku")
    @Annotation(key = "vmf:jackson:rename", value = "stock")
    StockEntry[] getItems();
}

@InterfaceOnly interface CombinedStock extends StockOwner, BackupOwner {}

@InterfaceOnly interface WarehouseHeader { String getLabel(); }

interface Warehouse extends WarehouseHeader, CombinedStock {
    @Override
    @Annotation(key = "vmf:jackson:rename", value = "receipts")
    StockEntry[] getItems();
}

interface StandardStock extends CombinedStock {}

interface DirectStock extends StockOwner, BackupOwner {
    @Override
    @Annotation(key = "vmf:jackson:rename", value = "direct_stock")
    StockEntry[] getItems();
}
