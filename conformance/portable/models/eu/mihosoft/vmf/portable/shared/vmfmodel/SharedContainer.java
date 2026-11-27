package eu.mihosoft.vmf.portable.shared.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

// From test-suite vmftest/containment (containmentMultiplePropsTestMultipleOpposites):
// one container property that is the opposite of several containment properties.
interface ContainerMultipleOpposites {
    @Contains(opposite = "parent")
    ElementMultipleOpposites getElement();

    @Contains(opposite = "parent")
    ElementMultipleOpposites getElement1();

    @Contains(opposite = "parent")
    ElementMultipleOpposites[] getElements();

    @Contains(opposite = "parent")
    ElementMultipleOpposites[] getElements1();
}

interface ElementMultipleOpposites {
    @Container()
    ContainerMultipleOpposites getParent();
}
