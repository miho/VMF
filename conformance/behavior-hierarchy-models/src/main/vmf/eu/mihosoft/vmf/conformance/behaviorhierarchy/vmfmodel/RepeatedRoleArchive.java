package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.Contains;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.RepeatedRoleOracle.RootBinding")
interface RepeatedRoot {
    @DefaultValue("11") int getCount();
    int choose(int input);
    int rootCalls();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.RepeatedRoleOracle.FirstBinding")
interface RepeatedFirst {
    int choose(int input);
    int fromFirst();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.RepeatedRoleOracle.SecondBinding")
interface RepeatedSecond {
    int choose(int input);
    int fromSecond();
}

interface RepeatedLeft extends RepeatedRoot, RepeatedFirst { }
interface RepeatedRight extends RepeatedSecond, RepeatedRoot { }
interface RepeatedLeftWins extends RepeatedLeft, RepeatedRight { }
interface RepeatedRightWins extends RepeatedRight, RepeatedLeft { }
interface RepeatedDirectEdge extends RepeatedLeft, RepeatedRoot { }

interface RepeatedRoleArchive {
    @Contains RepeatedRoot[] getItems();
    RepeatedLeft getSelected();
}
