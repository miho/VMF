package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

interface SharedPrimary {
    @DefaultValue("11") int getLevel();
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SharedRoleExchange.SharedBinding")
    int one();
    @DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SharedRoleExchange.SharedBinding")
    int evaluate(int value);
}

@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SharedRoleExchange.SharedBinding")
interface SharedRole {
    @DefaultValue("\"role\"") String getLabel();
    int two();
    int evaluate(int value);
    int evaluate(String value);
}

interface SharedJoined extends SharedPrimary, SharedRole { }

interface SharedReversed extends SharedRole, SharedPrimary { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SharedRoleExchange.SharedBinding")
interface SharedLocal extends SharedPrimary, SharedRole {
    @Override int evaluate(int value);
}

interface SharedArchive {
    @Contains SharedPrimary[] getItems();
    SharedRole getSelected();
}
