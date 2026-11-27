package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.NestedRoleExchange.First")
interface NestedFirst {
    @DefaultValue("11") int getFirstValue();
    int choose(int input);
    int firstCalls();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.NestedRoleExchange.Second")
interface NestedSecond {
    @DefaultValue("22") int getSecondValue();
    int choose(int input);
    int choose(long input);
    int secondCalls();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.NestedRoleExchange.Third")
interface NestedThird {
    @DefaultValue("33") int getThirdValue();
    int choose(int input);
    int choose(String input);
    int thirdCalls();
}

interface NestedPair extends NestedFirst, NestedSecond { }

interface NestedPrimary extends NestedPair, NestedThird { }

interface NestedSecondary extends NestedThird, NestedPair { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.NestedRoleExchange.Inner")
interface NestedInner extends NestedFirst, NestedSecond {
    @DefaultValue("44") int getInnerValue();
    @Override int choose(int input);
    int innerCalls();
}

interface NestedInnerPrimary extends NestedInner, NestedThird { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.NestedRoleExchange.Outer")
interface NestedOuter extends NestedThird, NestedInner {
    @Override int choose(int input);
}

interface NestedRoleArchive {
    @Contains NestedThird[] getItems();
    NestedFirst getSelected();
}
