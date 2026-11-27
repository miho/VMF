package eu.mihosoft.vmf.portable.collections.vmfmodel;

import eu.mihosoft.vcollections.VMap;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Immutable;
import eu.mihosoft.vmf.core.Keyed;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.Refers;

import java.util.List;
import java.util.Set;

// The collections module: sets, ordered sets, bounded lists, maps and keyed containment.
// From test-suite vmftest/multiplicity, managedmap, keyed, collectioncontract and referenceends (2025, 2026).
interface Catalog {
    Set<String> getTags();

    @Multiplicity(upper = 2)
    Set<Integer> getCodes();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET, upper = 3)
    List<String> getStages();

    @Multiplicity(upper = 2)
    List<Integer> getSamples();

    // An ordinary list: no bound, null elements allowed.
    String[] getNotes();

    VMap<String, Integer> getPorts();

    @Multiplicity(upper = 2)
    VMap<Integer, String> getLabels();

    @Contains(opposite = "catalog")
    @Keyed(by = "sku")
    Item[] getItems();

    @Contains
    @Keyed(by = "target")
    Binding[] getBindings();

    // Bounds of model collections are checked by validation only.
    @Contains
    @Multiplicity(upper = 1)
    Item[] getSpares();

    Settings getSettings();
}

interface Item {
    String getSku();

    String getName();

    @Container(opposite = "items")
    Catalog getCatalog();
}

// Keyed by a model reference: keys compare by identity.
interface Binding {
    Item getTarget();

    String getNote();
}

@Immutable
interface Settings {
    Set<String> getFlags();

    VMap<String, Boolean> getSwitches();

    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    List<String> getSteps();
}

// Ordered and unordered reference sets, with and without an opposite.
interface Picks {
    @Multiplicity(kind = Multiplicity.Kind.ORDERED_SET)
    Item[] getChoices();

    Set<Item> getFavorites();
}

interface Team {
    @Refers(opposite = "teams")
    Set<Member> getMembers();
}

interface Member {
    String getName();

    @Refers(opposite = "members")
    Team[] getTeams();
}
