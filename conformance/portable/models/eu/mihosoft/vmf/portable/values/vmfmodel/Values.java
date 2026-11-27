package eu.mihosoft.vmf.portable.values.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.DefaultValue;

// From test-suite vmftest/defaultvaluesandbuilders, vmftest/lazyinit, vmftest/builders
// and vmftest/observableprop, restricted to boolean, int and String.
interface Basic {
    boolean getFlag();

    int getCount();

    String getText();

    Integer getOptionalCount();

    String[] getTags();

    Integer[] getNumbers();
}

interface WithDefaultValues {
    @DefaultValue(value = "true")
    boolean isVisible();

    @DefaultValue(value = "\"my name\"")
    String getName();

    @DefaultValue(value = "42")
    int getAnswer();
}

interface Obj {
    Entry[] getEntries();
}

interface Entry {
    String getName();
}

interface AClass {
    String getName();

    String[] getIds();

    @Contains
    Child[] getChildren();

    @Contains
    Child2 getChild();
}

interface Child {
    int getValue();
}

interface Child2 {
    int getValue();
}
