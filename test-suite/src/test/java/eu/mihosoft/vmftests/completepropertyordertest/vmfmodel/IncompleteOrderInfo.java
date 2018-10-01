package eu.mihosoft.vmftests.completepropertyordertest.vmfmodel;

import eu.mihosoft.vmf.core.PropertyOrder;

public interface IncompleteOrderInfo {
    String getA();

    @PropertyOrder(index = 2)
    String getB();
}

