package eu.mihosoft.vmftests.completepropertyordertest.vmfmodel;

import eu.mihosoft.vmf.core.PropertyOrder;

public interface InvalidOrderInfo {
    @PropertyOrder(index = 1)
    String getA();

    @PropertyOrder(index = 1)
    String getB();

    @PropertyOrder(index = 3)
    String getC();
}
