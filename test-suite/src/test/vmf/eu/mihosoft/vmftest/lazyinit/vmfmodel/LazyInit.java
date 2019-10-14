package eu.mihosoft.vmftest.lazyinit.vmfmodel;

import eu.mihosoft.vmf.core.VMFEquals;

@VMFEquals
interface Obj {
    Entry[] getEntries();
}

@VMFEquals
interface Entry {
    String getName();
}