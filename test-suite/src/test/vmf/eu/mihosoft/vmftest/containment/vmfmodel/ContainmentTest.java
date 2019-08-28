package eu.mihosoft.vmftest.containment.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface ContainerOne {
    @Contains()
    Element getElement();

    @Contains(opposite = "parentOne")
    Element getElement1();

    @Contains()
    Element[] getElements1();

    @Contains(opposite="listParentOne")
    Element[] getElements1a();
}

interface ContainerTwo {
    @Contains()
    Element getElement();

    @Contains(opposite = "parentTwo")
    Element getElement2();

    @Contains()
    Element[] getElements2();

    @Contains(opposite="listParentTwo")
    Element[] getElements2a();
}

interface Element {
    @Container(opposite = "element1")
    ContainerOne getParentOne();

    @Container(opposite = "element2")
    ContainerTwo getParentTwo();

    @Container(opposite = "elements1a")
    ContainerOne getListParentOne();

    @Container(opposite = "elements2a")
    ContainerTwo getListParentTwo();
}
