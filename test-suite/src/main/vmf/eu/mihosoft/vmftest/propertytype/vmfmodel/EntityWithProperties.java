package eu.mihosoft.vmftest.propertytype.vmfmodel;

interface EntityWithProperties {

    Integer[] getIds();

    ChildEntity[] getChildren();

    ChildEntity getEntity();

}

interface ChildEntity {

    String getName();

}