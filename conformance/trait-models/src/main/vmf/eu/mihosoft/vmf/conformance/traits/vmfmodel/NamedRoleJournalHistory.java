package eu.mihosoft.vmf.conformance.traits.vmfmodel;

import eu.mihosoft.vmf.core.Contains;

interface NamedRoleJournalHistory {
    @Contains NamedRoleFolder getLeft();
    @Contains NamedRoleFolder getRight();
    NamedOwnedRole getSelected();
}
