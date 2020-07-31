package eu.mihosoft.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface Parent {

    @Contains(opposite = "parent")
    Child[] getChildren();

    String getName();
}

interface Child {
    @Container(opposite="children")
    Parent getParent();
    
    int getValue();
}