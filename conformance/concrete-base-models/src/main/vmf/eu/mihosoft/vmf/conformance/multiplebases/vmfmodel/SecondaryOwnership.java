package eu.mihosoft.vmf.conformance.multiplebases.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Multiplicity;
import eu.mihosoft.vmf.core.VMFEquals;

import java.util.Set;

interface OwnerHeader {
    String getTitle();
}

interface RecordOwner {
    @Contains(opposite = "owner")
    @Multiplicity(upper = 2)
    Set<OwnedRecord> getRecords();
}

interface JoinedOwner extends OwnerHeader, RecordOwner {
    String getId();
}

@VMFEquals(VMFEquals.EqualsType.ALL)
interface OwnedRecord {
    String getName();
    @Container(opposite = "records")
    RecordOwner getOwner();
}

interface OwnershipEnvelope {
    @Contains RecordOwner getOwner();
    OwnedRecord getFeatured();
}
