package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;
import eu.mihosoft.vmf.core.InterfaceOnly;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.TrioRoleExchange.TrioSharedBinding")
interface TrioFirst {
    @DefaultValue("11") int getFirstValue();
    int evaluate(int value);
    int firstCalls();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.TrioRoleExchange.TrioIndependentBinding")
interface TrioSecond {
    @DefaultValue("22") int getSecondValue();
    int evaluate(int value);
    int evaluate(long value);
    long finish(String marker);
    int secondCalls();
}

@InterfaceOnly
@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.TrioRoleExchange.TrioSharedBinding")
interface TrioThird {
    @DefaultValue("33") int getThirdValue();
    int evaluate(int value);
    int evaluate(String value);
    long finish(String marker);
    int thirdCalls();
}

interface TrioForward extends TrioFirst, TrioSecond, TrioThird { }

interface TrioReverse extends TrioSecond, TrioThird, TrioFirst { }

interface TrioInherited extends TrioForward { }

interface TrioArchive {
    @Contains TrioFirst[] getItems();
    TrioThird getSelected();
}
