package eu.mihosoft.vmftest.staticreflection.vmfmodel;

interface Root {
    TypeC getProp();
}

interface TypeA extends Root {

    int getPropA1();
    String getPropA2();

}

interface TypeB extends Root {
    double getPropB1();
    TypeA getPropB2();
}

interface TypeC extends TypeA, TypeB {
    String getName();
}
