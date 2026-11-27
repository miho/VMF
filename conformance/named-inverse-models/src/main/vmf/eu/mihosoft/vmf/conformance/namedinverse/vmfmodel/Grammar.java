package eu.mihosoft.vmf.conformance.namedinverse.vmfmodel;

import eu.mihosoft.vmf.core.Container;
import eu.mihosoft.vmf.core.Contains;

interface Grammar {
    @Contains(opposite = "grammar") Rule[] getRules();
}

interface Rule {
    String getName();
    @Container(opposite = "rules") Grammar getGrammar();
    @Contains(opposite = "parent") Member[] getProperties();
    @Contains(opposite = "parent") Member[] getCustomProperties();
}

interface Member {
    String getName();
    @Container(opposite = "properties") Rule getParent();
}
