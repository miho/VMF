package eu.mihosoft.vmftest.collectioncontract.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;
import eu.mihosoft.vmf.core.Refers;
import eu.mihosoft.vmf.core.IgnoreEquals;
import eu.mihosoft.vcollections.VMap;
import java.util.Set;

interface Catalog {
    @Contains(opposite = "catalog")
    @Multiplicity(lower = 1, upper = 2)
    Item[] getItems();

    @Multiplicity(upper = 3)
    Set<String> getTags();

    @Multiplicity(upper = 2)
    VMap<Integer, String> getLabels();
}

@VMFEquals(VMFEquals.EqualsType.CONTAINMENT_AND_EXTERNAL)
interface Item {
    @Container(opposite = "items")
    Catalog getCatalog();

    @Multiplicity(lower = 1)
    String getName();
}

@VMFEquals(VMFEquals.EqualsType.ALL)
interface Member {
    String getName();
    @IgnoreEquals
    @Refers(opposite = "members")
    Team[] getTeams();
}

interface Team {
    @Refers(opposite = "teams")
    Member[] getMembers();
}
