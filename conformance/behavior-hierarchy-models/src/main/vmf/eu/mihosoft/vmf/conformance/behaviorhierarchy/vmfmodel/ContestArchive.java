package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

@InterfaceOnly
interface ContestPrimaryData { @DefaultValue("\"main\"") String getTitle(); }

@InterfaceOnly
interface ContestRoleData { @DefaultValue("\"side\"") String getLabel(); }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.RoleContestExchange.PrimaryBinding")
interface ContestPrimary extends ContestPrimaryData {
    int evaluate(int value);
    int primaryCalls();
}

@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.RoleContestExchange.RoleBinding")
interface ContestRole extends ContestRoleData {
    int evaluate(int value);
    int evaluate(long value);
    int evaluate(String value);
    int roleCalls();
}

interface ContestPrimaryWins extends ContestPrimary, ContestRole { }

interface ContestRoleWins extends ContestRole, ContestPrimary { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.RoleContestExchange.OverrideBinding")
interface ContestOverride extends ContestPrimary, ContestRole {
    @Override int evaluate(int value);
}

interface ContestArchive {
    @Contains ContestPrimary[] getItems();
    ContestRole getSelected();
}
