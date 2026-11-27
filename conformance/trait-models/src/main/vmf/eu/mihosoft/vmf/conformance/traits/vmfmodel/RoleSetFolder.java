package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.GetterOnly;
import eu.mihosoft.vmf.core.InterfaceOnly;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.Set;

@VMFEquals(VMFEquals.EqualsType.ALL)
interface RoleKey {
    String getCode();
}

@InterfaceOnly
interface RoleSetItem {
    @GetterOnly RoleKey getToken();
}

interface RoleSetTask extends AssetData, RoleSetItem {
    String getTaskId();
}

interface RoleSetNote extends RoleSetItem {
    String getMessage();
}

interface RoleSetFolder {
    @Contains @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) RoleSetItem[] getOrdered();
    @Contains Set<RoleSetItem> getUnordered();
    @Contains @Keyed(by = "token") RoleSetItem[] getIndexed();
    @Contains @Keyed(by = "token") @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET) RoleSetItem[] getOrderedIndex();
    @Contains RoleKey[] getKeys();
    RoleSetItem getSelected();
}
