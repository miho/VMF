package eu.mihosoft.vmftest.diff.vmfmodel;

import eu.mihosoft.vmf.core.Contains;
import eu.mihosoft.vmf.core.Doc;

@Doc("Model node to diff example model.")
interface NodeToDiff {

    @Doc("Name of this node.")
    String getName();

    @Doc("Singular value of this node.")
    int getValue();

    @Doc("Value collection of this node.")
    Integer[] getValues();

    @Doc("Child node of this node.")
    @Contains
    NodeToDiff getChild();

    @Doc("Children of this node.")
    @Contains
    NodeToDiff[] getChildren();
}




