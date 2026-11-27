package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Contains;

interface KeyedUnorderedHistory {
    @Contains NamedKeyedUnorderedFolder getLeft();
    @Contains NamedKeyedUnorderedFolder getRight();
    @Contains RoleKey[] getKeys();
    RoleSetItem getSelected();
}

interface NamedKeyedUnorderedFolder extends UnorderedKeyedFolder {
    String getName();
}
