package eu.mihosoft.vmftests.completepropertyordertest.vmfmodel;

import eu.mihosoft.vmf.core.PropertyOrder;

public interface CompleteOrderInfo {

    @PropertyOrder(index = 1)
    String getA();

    @PropertyOrder(index = 2)
    String getB();
}
