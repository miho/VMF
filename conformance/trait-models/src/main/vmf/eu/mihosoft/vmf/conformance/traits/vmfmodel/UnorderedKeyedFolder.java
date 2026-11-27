package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Keyed;

import java.util.Set;

interface UnorderedKeyedFolder {
    @Contains @Keyed(by = "token") Set<RoleSetItem> getItems();
    @Contains RoleKey[] getKeys();
    RoleSetItem getSelected();
    @Contains @Keyed(by = "key") Set<KeyedItem> getTagged();
}

interface UnorderedKeyedExactFolder {
    @Contains @Keyed(by = "key") Set<UnorderedKeyedExactItem> getItems();
}

interface UnorderedKeyedExactItem {
    String getKey();
    String getMessage();
}
