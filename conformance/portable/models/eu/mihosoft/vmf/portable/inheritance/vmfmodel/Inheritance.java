package eu.mihosoft.vmf.portable.inheritance.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.InterfaceOnly;

// Single inheritance, interface-only bases and polymorphic containment declared on a base type.
@InterfaceOnly
interface Named {
    String getName();
}

interface Base extends Named {
    int getId();

    @Container(opposite = "entries")
    Folder getFolder();
}

interface Derived extends Base {
    String getExtra();
}

interface Document extends Base {
    String getText();
}

interface Folder extends Named {
    @Contains(opposite = "folder")
    Base[] getEntries();
}
