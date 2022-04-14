package eu.mihosoft.vmftest.builders.vmfmodel;

import eu.mihosoft.vmf.core.DefaultValue;

interface AClass {
    String getName();

    String[] getIds();

    Child[] getChildren();

    Child2 getChild();
}

interface Child {
    int getValue();
}

interface Child2 {
    int getValue();
}
