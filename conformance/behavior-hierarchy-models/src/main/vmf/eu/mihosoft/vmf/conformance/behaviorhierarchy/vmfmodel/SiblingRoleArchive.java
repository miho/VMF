package eu.mihosoft.vmf.conformance.behaviorhierarchy.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;
import eu.mihosoft.vmf.core.DelegateTo;

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SiblingRoleOracle.RootBinding")
interface SiblingRoot {
    @DefaultValue("11") int getValue();
    int choose(int value);
    int rootCalls();
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SiblingRoleOracle.SidesBinding")
interface SiblingLeft extends SiblingRoot {
    int left();
    int shared(int value);
}

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SiblingRoleOracle.SidesBinding")
interface SiblingRight extends SiblingRoot {
    int right();
    int shared(int value);
}

interface SiblingPlain extends SiblingLeft, SiblingRight { }

interface SiblingReversed extends SiblingRight, SiblingLeft { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SiblingRoleOracle.OverrideBinding")
interface SiblingRightOverride extends SiblingRoot {
    @Override int choose(int value);
    int overrideCalls();
}

interface SiblingFirstPath extends SiblingLeft, SiblingRightOverride { }

interface SiblingSecondPath extends SiblingRightOverride, SiblingLeft { }

@DelegateTo(className = "eu.mihosoft.vmf.conformance.behaviorhierarchy.SiblingRoleOracle.LocalBinding")
interface SiblingLocal extends SiblingLeft, SiblingRightOverride {
    @Override int choose(int value);
}

interface SiblingRoleArchive {
    @Contains SiblingRoot[] getItems();
    SiblingLeft getSelected();
}
