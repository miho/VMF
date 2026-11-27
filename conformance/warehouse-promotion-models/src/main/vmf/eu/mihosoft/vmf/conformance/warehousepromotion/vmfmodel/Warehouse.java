package eu.mihosoft.vmf.conformance.warehousepromotion.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.VMFEquals;

@InterfaceOnly interface HasSku<S> { S getSku(); }

@VMFEquals(VMFEquals.EqualsType.ALL)
interface Item extends HasSku<String> {}

@InterfaceOnly interface Inventory {
    @Contains @Keyed(by = "sku") Item[] getItems();
}

@InterfaceOnly interface ExtraInventory {
    @Contains @Keyed(by = "sku") Item[] getItems();
}

@InterfaceOnly interface UnkeyedInventory { Item[] getItems(); }

interface Warehouse extends UnkeyedInventory, ExtraInventory, Inventory {}

interface LooseWarehouse extends UnkeyedInventory {}

@InterfaceOnly interface LooseHeader { String getLabel(); }

interface LooseOverlay extends LooseHeader, UnkeyedInventory {}
