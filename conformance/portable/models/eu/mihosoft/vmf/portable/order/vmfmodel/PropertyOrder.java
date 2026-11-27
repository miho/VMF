package eu.mihosoft.vmf.portable.order.vmfmodel;

import eu.mihosoft.vmf.core.PropertyOrder;

// From test-suite vmftest/propertyorder (PropertyOrderTest, 2018), with int, boolean and String
// instead of Integer and double.
interface DefaultOrder {
    String getZ();

    Element getB();

    int getD();

    boolean getX();
}

interface CustomOrder {
    @PropertyOrder(index = 3)
    int getD();

    @PropertyOrder(index = 1)
    String getZ();

    @PropertyOrder(index = 2)
    Element getB();

    @PropertyOrder(index = 4)
    boolean getX();
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

// Neither the base nor the subtype declares an order.
interface PlainBase {
    String getZeta();
    String getMid();
}

interface PlainSub extends PlainBase {
    String getAlpha();
    String getYank();
}
