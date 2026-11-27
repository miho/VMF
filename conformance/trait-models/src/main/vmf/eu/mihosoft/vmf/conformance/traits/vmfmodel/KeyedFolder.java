package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;

@InterfaceOnly
interface KeyedItem {
    @GetterOnly @DefaultValue("\"reset\"") String getKey();
}

interface KeyedTask extends AssetData, KeyedItem {
    String getTaskId();
}

interface KeyedNote extends KeyedItem {
    String getMessage();
}

interface KeyedFolder {
    @Contains @Keyed(by = "key") KeyedItem[] getEntries();
    KeyedItem getSelected();
}
