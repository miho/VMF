package eu.mihosoft.vmftest.delegationinherit.vmfmodel;

import eu.mihosoft.vmf.core.*;


@InterfaceOnly
interface Producer {
    void produce();
}

@InterfaceOnly
interface Consumer {
    void consume();
}

@InterfaceOnly
interface Processor extends Producer, Consumer {
    void process();
}

interface Device extends Processor {

    @DelegateTo(className="eu.mihosoft.vmftest.delegationinherit.DeviceDelegate")
    void process();
    @DelegateTo(className="eu.mihosoft.vmftest.delegationinherit.DeviceDelegate")
    void consume();
    @DelegateTo(className="eu.mihosoft.vmftest.delegationinherit.DeviceDelegate")
    void produce();
}

@DelegateTo(className="eu.mihosoft.vmftest.delegationinherit.CircuitDeviceDelegate")
interface CircuitDevice extends Device {

    // uses constructor delegation info
    void process();
    // uses constructor delegation info
    void consume();
    @DelegateTo(className="eu.mihosoft.vmftest.delegationinherit.CircuitDeviceDelegate")
    void produce();
}
