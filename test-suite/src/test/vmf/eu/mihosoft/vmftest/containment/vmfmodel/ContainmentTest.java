package eu.mihosoft.vmftest.containment.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface ContainerOne {
    @Contains()
    Element getElement();

    @Contains(opposite = "parentOne")
    Element getElement1();
}

interface ContainerTwo {
    @Contains()
    Element getElement();

    @Contains(opposite = "parentTwo")
    Element getElement2();
}

interface Element {
    @Container(opposite = "element1")
    ContainerOne getParentOne();

    @Container(opposite = "element2")
    ContainerTwo getParentTwo();
}
