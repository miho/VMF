package eu.mihosoft.vmftest.lazyinit.vmfmodel;

interface Obj {
    Entry[] getEntries();
}

interface Entry {
    String getName();
}