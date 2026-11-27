package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

interface Result { String getName(); }
interface RankedResult extends Result { int getRank(); }

@InterfaceOnly
interface ResultData { @Contains RankedResult getStored(); }

interface NeutralRole {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.CovariantRoleOracle.Neutral") int baseline();
}

interface WideRole extends ResultData {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.CovariantRoleOracle.Wide") Result choose();
}

interface NarrowRole extends ResultData {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.CovariantRoleOracle.Narrow") RankedResult choose();
}

interface NarrowFirst extends NarrowRole, WideRole { }

interface SecondaryNarrow extends NeutralRole, NarrowRole, WideRole { }

interface SharedWide extends ResultData {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.CovariantRoleOracle.Shared") Result sample();
}

interface SharedNarrow extends ResultData {
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.CovariantRoleOracle.Shared") RankedResult sample();
}

interface SharedNarrowFirst extends SharedNarrow, SharedWide { }

interface CovariantRoleArchive {
    @Contains ResultData[] getItems();
    WideRole getSelected();
}
