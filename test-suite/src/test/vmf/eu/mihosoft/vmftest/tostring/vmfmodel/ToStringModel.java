package eu.mihosoft.vmftest.tostring.vmfmodel;

// we want to test toString() with circular dependency and without containment

interface Parent {
    Child getChild();

    String getName();
}

interface Child {
    Parent getParent();

    String getName();
}

// and here we test with collections

interface Parent2 {
    Child2[] getChildren();

    String getName();
}

interface Child2 {
    Parent2 getParent();

    String getName();
}