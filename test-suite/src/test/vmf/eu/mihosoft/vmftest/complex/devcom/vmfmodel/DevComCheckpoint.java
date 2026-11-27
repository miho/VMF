package eu.mihosoft.vmftest.complex.devcom.vmfmodel;

/** Mutable checkpoint for the DevCom journal conformance exchange. */
interface DevComCheckpoint {
    PortConfig getConfig();
    PortInfo getPort();
    StateChangedEvent getEvent();
}
