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