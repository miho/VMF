package eu.mihosoft.vmftest.propertyorder.vmfmodel;

import eu.mihosoft.vmf.core.PropertyOrder;

interface DefaultOrder {
    String getZ();

    Element getB();

    Integer getD();

    double getX();
}

interface CustomOrder {

    @PropertyOrder(index = 3)
    Integer getD();

    @PropertyOrder(index = 1)
    String getZ();

    @PropertyOrder(index = 2)
    Element getB();

    @PropertyOrder(index =4)
    double getX();
}

interface Element {

}

interface InheritedBaseWithoutCustomOrder {
    String getBaseA();
    String getBaseZ();
    String getBaseB();
}

interface InheritedOrderSubClassWithoutBaseOrder extends InheritedBaseWithoutCustomOrder {

    @PropertyOrder(index = 0)
    String getA();
    @PropertyOrder(index = 1)
    String getZ();
    @PropertyOrder(index = 2)
    String getB();
}

interface InheritedBaseWithCustomOrder {
    @PropertyOrder(index = 0)
    String getBaseA();
    @PropertyOrder(index = 1)
    String getBaseZ();
    @PropertyOrder(index = 2)
    String getBaseB();
}

interface InheritedOrderSubClassWithBaseOrder extends InheritedBaseWithCustomOrder {

    @PropertyOrder(index = 0)
    String getA();
    @PropertyOrder(index = 1)
    String getZ();
    @PropertyOrder(index = 2)
    String getB();
}